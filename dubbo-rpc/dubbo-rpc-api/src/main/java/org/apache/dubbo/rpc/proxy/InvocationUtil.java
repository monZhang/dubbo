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

package org.apache.dubbo.rpc.proxy;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.profiler.Profiler;
import org.apache.dubbo.common.profiler.ProfilerEntry;
import org.apache.dubbo.common.profiler.ProfilerSwitch;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.RpcInvocation;
import org.apache.dubbo.rpc.RpcServiceContext;

import static org.apache.dubbo.common.constants.CommonConstants.DEFAULT_TIMEOUT;
import static org.apache.dubbo.common.constants.CommonConstants.TIMEOUT_KEY;

public class InvocationUtil {
    private static final Logger logger = LoggerFactory.getLogger(InvokerInvocationHandler.class);

    public static Object invoke(Invoker<?> invoker, RpcInvocation rpcInvocation) throws Throwable {
        URL url = invoker.getUrl();
        String serviceKey = url.getServiceKey();
        rpcInvocation.setTargetServiceUniqueName(serviceKey);

        // invoker.getUrl() returns consumer url.
        //创建 RpcServiceContext 保存到当前线程中(使用自己构建的threadLocal) 并保存consumerUrl信息
        RpcServiceContext.getServiceContext().setConsumerUrl(url);

        if (ProfilerSwitch.isEnableSimpleProfiler()) {
            ProfilerEntry parentProfiler = Profiler.getBizProfiler();
            ProfilerEntry bizProfiler;
            if (parentProfiler != null) {
                bizProfiler = Profiler.enter(parentProfiler,
                    "Receive request. Client invoke begin. ServiceKey: " + serviceKey + " MethodName:" + rpcInvocation.getMethodName());
            } else {
                bizProfiler = Profiler.start("Receive request. Client invoke begin. ServiceKey: " + serviceKey + " " + "MethodName:" + rpcInvocation.getMethodName());
            }
            rpcInvocation.put(Profiler.PROFILER_KEY, bizProfiler);
            try {
                //执行服务调用, 并对返回的结果进行处理
                return invoker.invoke(rpcInvocation).recreate();
            } finally {
                Profiler.release(bizProfiler);
                //todo: 超时时间的获取以服务端为准 ???
                int timeout;
                Object timeoutKey = rpcInvocation.getObjectAttachmentWithoutConvert(TIMEOUT_KEY);
                if (timeoutKey instanceof Integer) {
                    timeout = (Integer) timeoutKey;
                } else {
                    //获取方法的超时时间, 默认1s
                    timeout = url.getMethodPositiveParameter(rpcInvocation.getMethodName(), TIMEOUT_KEY, DEFAULT_TIMEOUT);
                }
                //判断一下invoker执行是否超时, 如果超时那么就打印一行警告日志.
                long usage = bizProfiler.getEndTime() - bizProfiler.getStartTime();
                if ((usage / (1000_000L * ProfilerSwitch.getWarnPercent())) > timeout) {
                    StringBuilder attachment = new StringBuilder();
                    rpcInvocation.foreachAttachment((entry) -> {
                        attachment.append(entry.getKey()).append("=").append(entry.getValue()).append(";\n");
                    });

                    logger.warn(String.format(
                        "[Dubbo-Consumer] execute service %s#%s cost %d.%06d ms, this invocation almost (maybe already) timeout. Timeout: %dms\n" + "invocation context:\n%s" + "thread info: \n%s",
                        rpcInvocation.getProtocolServiceKey(),
                        rpcInvocation.getMethodName(),
                        usage / 1000_000,
                        usage % 1000_000,
                        timeout,
                        attachment,
                        Profiler.buildDetail(bizProfiler)));
                }
            }
        }
        //无探查器情况直接调用
        return invoker.invoke(rpcInvocation).recreate();
    }
}
