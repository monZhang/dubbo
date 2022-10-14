/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.dubbo.rpc.cluster.router.tag;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.config.configcenter.ConfigChangeType;
import org.apache.dubbo.common.config.configcenter.ConfigChangedEvent;
import org.apache.dubbo.common.config.configcenter.ConfigurationListener;
import org.apache.dubbo.common.config.configcenter.DynamicConfiguration;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.Holder;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.cluster.router.RouterSnapshotNode;
import org.apache.dubbo.rpc.cluster.router.state.AbstractStateRouter;
import org.apache.dubbo.rpc.cluster.router.state.BitList;
import org.apache.dubbo.rpc.cluster.router.tag.model.TagRouterRule;
import org.apache.dubbo.rpc.cluster.router.tag.model.TagRuleParser;

import java.util.List;
import java.util.function.Predicate;

import static org.apache.dubbo.common.constants.CommonConstants.ANYHOST_VALUE;
import static org.apache.dubbo.common.constants.CommonConstants.TAG_KEY;
import static org.apache.dubbo.rpc.Constants.FORCE_USE_TAG;

/**
 * TagRouter, "application.tag-router"
 */
public class TagStateRouter<T> extends AbstractStateRouter<T> implements ConfigurationListener {
    public static final String NAME = "TAG_ROUTER";
    private static final Logger logger = LoggerFactory.getLogger(TagStateRouter.class);
    private static final String RULE_SUFFIX = ".tag-router";

    private TagRouterRule tagRouterRule;
    private String application;

    public TagStateRouter(URL url) {
        super(url);
    }

    @Override
    public synchronized void process(ConfigChangedEvent event) {
        if (logger.isDebugEnabled()) {
            logger.debug("Notification of tag rule, change type is: " + event.getChangeType() + ", raw rule is:\n " +
                event.getContent());
        }

        try {
            if (event.getChangeType().equals(ConfigChangeType.DELETED)) {
                this.tagRouterRule = null;
            } else {
                this.tagRouterRule = TagRuleParser.parse(event.getContent());
            }
        } catch (Exception e) {
            logger.error("Failed to parse the raw tag router rule and it will not take effect, please check if the " +
                "rule matches with the template, the raw rule is:\n ", e);
        }
    }

    @Override
    public BitList<Invoker<T>> doRoute(BitList<Invoker<T>> invokers, URL url, Invocation invocation, boolean needToPrintMessage, Holder<RouterSnapshotNode<T>> nodeHolder, Holder<String> messageHolder) throws RpcException {
        if (CollectionUtils.isEmpty(invokers)) {
            if (needToPrintMessage) {
                messageHolder.set("Directly Return. Reason: Invokers from previous router is empty.");
            }
            return invokers;
        }

        // 1. 静态tag路由
        // since the rule can be changed by config center, we should copy one to use.
        final TagRouterRule tagRouterRuleCopy = tagRouterRule;
        if (tagRouterRuleCopy == null || !tagRouterRuleCopy.isValid() || !tagRouterRuleCopy.isEnabled()) {
            if (needToPrintMessage) {
                messageHolder.set("Disable Tag Router. Reason: tagRouterRule is invalid or disabled");
            }
            //过滤条件
            // 1.1.tag完全匹配 (tag不存在也是一种过滤条件)
            // 1.2.tag匹配失败&&不是强制tag路由时, 返回没有tag的invoker
            return filterUsingStaticTag(invokers, url, invocation);
        }

        //2. 动态tag路由
        BitList<Invoker<T>> result = invokers;
        String tag = StringUtils.isEmpty(invocation.getAttachment(TAG_KEY)) ? url.getParameter(TAG_KEY) :
            invocation.getAttachment(TAG_KEY);

        // if we are requesting for a Provider with a specific tag
        if (StringUtils.isNotEmpty(tag)) {
            List<String> addresses = tagRouterRuleCopy.getTagnameToAddresses().get(tag);
            // filter by dynamic tag group first
            if (CollectionUtils.isNotEmpty(addresses)) {
                // 2.1.1. 动态路由中指定了地址, 那么地址需要和动态路由中配置相同
                result = filterInvoker(invokers, invoker -> addressMatches(invoker.getUrl(), addresses));
                // if result is not null OR it's null but force=true, return result directly
                if (CollectionUtils.isNotEmpty(result) || tagRouterRuleCopy.isForce()) {
                    if (needToPrintMessage) {
                        messageHolder.set("Use tag " + tag + " to route. Reason: result is not null OR it's null but force=true");
                    }
                    return result;
                }
            } else {
                // 2.1.2. 动态路由中没有指定地址, 那么使用tag判断
                // dynamic tag group doesn't have any item about the requested app OR it's null after filtered by
                // dynamic tag group but force=false. check static tag
                result = filterInvoker(invokers, invoker -> tag.equals(invoker.getUrl().getParameter(TAG_KEY)));
            }
            // If there's no tagged providers that can match the current tagged request. force.tag is set by default
            // to false, which means it will invoke any providers without a tag unless it's explicitly disallowed.
            if (CollectionUtils.isNotEmpty(result) || isForceUseTag(invocation)) {
                if (needToPrintMessage) {
                    messageHolder.set("Use tag " + tag + " to route. Reason: result is not empty or ForceUseTag key is true in invocation");
                }
                return result;
            }
            // FAILOVER: return all Providers without any tags.
            else {
                // 2.1.3.动态路由tag过滤结果为空, 降级将没有tag, 地址不配置的invoker 选出来
                BitList<Invoker<T>> tmp = filterInvoker(invokers, invoker -> addressNotMatches(invoker.getUrl(),
                    tagRouterRuleCopy.getAddresses()));
                if (needToPrintMessage) {
                    messageHolder.set("FAILOVER: return all Providers without any tags");
                }
                return filterInvoker(tmp, invoker -> StringUtils.isEmpty(invoker.getUrl().getParameter(TAG_KEY)));
            }
        } else {
            //调用参数不指定tag情况 使用 2.1, 2.2 综合判断
            // List<String> addresses = tagRouterRule.filter(providerApp);
            // return all addresses in dynamic tag group.
            List<String> addresses = tagRouterRuleCopy.getAddresses();
            if (CollectionUtils.isNotEmpty(addresses)) {
                //2.2.1. 动态路由中指定了地址那么invoker地址需要不在动态路由策略中, 动态路由中未指定地址那么都满足
                result = filterInvoker(invokers, invoker -> addressNotMatches(invoker.getUrl(), addresses));
                // 1. all addresses are in dynamic tag group, return empty list.
                if (CollectionUtils.isEmpty(result)) {
                    if (needToPrintMessage) {
                        messageHolder.set("all addresses are in dynamic tag group, return empty list");
                    }
                    return result;
                }
                // 2. if there are some addresses that are not in any dynamic tag group, continue to filter using the
                // static tag group.
            }
            if (needToPrintMessage) {
                messageHolder.set("filter using the static tag group");
            }
            //2.2.2. invoker需要满足没有tag或者tag不在指定列表中
            return filterInvoker(result, invoker -> {
                String localTag = invoker.getUrl().getParameter(TAG_KEY);
                return StringUtils.isEmpty(localTag) || !tagRouterRuleCopy.getTagNames().contains(localTag);
            });
        }
    }

    /**
     * If there's no dynamic tag rule being set, use static tag in URL.
     * <p>
     * A typical scenario is a Consumer using version 2.7.x calls Providers using version 2.6.x or lower,
     * the Consumer should always respect the tag in provider URL regardless of whether a dynamic tag rule has been set to it or not.
     * <p>
     * TODO, to guarantee consistent behavior of interoperability between 2.6- and 2.7+, this method should has the same logic with the TagRouter in 2.6.x.
     *
     * @param invokers
     * @param url
     * @param invocation
     * @param <T>
     * @return
     */
    private <T> BitList<Invoker<T>> filterUsingStaticTag(BitList<Invoker<T>> invokers, URL url, Invocation invocation) {
        BitList<Invoker<T>> result;
        // Dynamic param
        String tag = StringUtils.isEmpty(invocation.getAttachment(TAG_KEY)) ? url.getParameter(TAG_KEY) :
            invocation.getAttachment(TAG_KEY);
        // Tag request
        if (!StringUtils.isEmpty(tag)) {
            //1.参数有tag, 进行tag匹配 (String.equals)
            result = filterInvoker(invokers, invoker -> tag.equals(invoker.getUrl().getParameter(TAG_KEY)));
            //根据tag匹配不到且不是强制走tag路由, 那么降级将没有tag标签的invoker筛选出来
            if (CollectionUtils.isEmpty(result) && !isForceUseTag(invocation)) {
                result = filterInvoker(invokers, invoker -> StringUtils.isEmpty(invoker.getUrl().getParameter(TAG_KEY)));
            }
        } else {
            //参数中没有tag, 那么invoker也需要没有tag
            result = filterInvoker(invokers, invoker -> StringUtils.isEmpty(invoker.getUrl().getParameter(TAG_KEY)));
        }
        return result;
    }

    @Override
    public boolean isRuntime() {
        return tagRouterRule != null && tagRouterRule.isRuntime();
    }

    @Override
    public boolean isForce() {
        // FIXME
        return tagRouterRule != null && tagRouterRule.isForce();
    }

    private boolean isForceUseTag(Invocation invocation) {
        return Boolean.parseBoolean(invocation.getAttachment(FORCE_USE_TAG, this.getUrl().getParameter(FORCE_USE_TAG, "false")));
    }

    private <T> BitList<Invoker<T>> filterInvoker(BitList<Invoker<T>> invokers, Predicate<Invoker<T>> predicate) {
        if (invokers.stream().allMatch(predicate)) {
            //全部满足条件, 直接返回
            return invokers;
        }

        //过滤掉不满足条件的部分invoker
        BitList<Invoker<T>> newInvokers = invokers.clone();
        newInvokers.removeIf(invoker -> !predicate.test(invoker));

        return newInvokers;
    }

    private boolean addressMatches(URL url, List<String> addresses) {
        return addresses != null && checkAddressMatch(addresses, url.getHost(), url.getPort());
    }

    private boolean addressNotMatches(URL url, List<String> addresses) {
        return addresses == null || !checkAddressMatch(addresses, url.getHost(), url.getPort());
    }

    private boolean checkAddressMatch(List<String> addresses, String host, int port) {
        for (String address : addresses) {
            try {
                if (NetUtils.matchIpExpression(address, host, port)) {
                    return true;
                }
                if ((ANYHOST_VALUE + ":" + port).equals(address)) {
                    return true;
                }
            } catch (Exception e) {
                logger.error("The format of ip address is invalid in tag route. Address :" + address, e);
            }
        }
        return false;
    }

    public void setApplication(String app) {
        this.application = app;
    }

    @Override
    public void notify(BitList<Invoker<T>> invokers) {
        if (CollectionUtils.isEmpty(invokers)) {
            return;
        }

        Invoker<T> invoker = invokers.get(0);
        URL url = invoker.getUrl();
        String providerApplication = url.getRemoteApplication();

        if (StringUtils.isEmpty(providerApplication)) {
            logger.error("TagRouter must getConfig from or subscribe to a specific application, but the application " +
                "in this TagRouter is not specified.");
            return;
        }

        synchronized (this) {
            if (!providerApplication.equals(application)) {
                if (StringUtils.isNotEmpty(application)) {
                    this.getRuleRepository().removeListener(application + RULE_SUFFIX, this);
                }
                String key = providerApplication + RULE_SUFFIX;
                this.getRuleRepository().addListener(key, this);
                application = providerApplication;
                String rawRule = this.getRuleRepository().getRule(key, DynamicConfiguration.DEFAULT_GROUP);
                if (StringUtils.isNotEmpty(rawRule)) {
                    this.process(new ConfigChangedEvent(key, DynamicConfiguration.DEFAULT_GROUP, rawRule));
                }
            }
        }
    }

    @Override
    public void stop() {
        if (StringUtils.isNotEmpty(application)) {
            this.getRuleRepository().removeListener(application + RULE_SUFFIX, this);
        }
    }
}
