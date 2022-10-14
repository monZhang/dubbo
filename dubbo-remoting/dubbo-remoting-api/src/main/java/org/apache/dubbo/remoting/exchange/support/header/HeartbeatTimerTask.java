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

package org.apache.dubbo.remoting.exchange.support.header;

import org.apache.dubbo.common.Version;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.exchange.Request;

import static org.apache.dubbo.common.constants.CommonConstants.HEARTBEAT_EVENT;

/**
 * HeartbeatTimerTask
 */
public class HeartbeatTimerTask extends AbstractTimerTask {

    private static final Logger logger = LoggerFactory.getLogger(HeartbeatTimerTask.class);

    private final int heartbeat;

    HeartbeatTimerTask(ChannelProvider channelProvider, Long heartbeatTick, int heartbeat) {
        super(channelProvider, heartbeatTick);
        this.heartbeat = heartbeat;
    }

    @Override
    protected void doTask(Channel channel) {
        try {
            Long lastRead = lastRead(channel);
            Long lastWrite = lastWrite(channel);
            Long now = now();
            if ((lastRead != null && now - lastRead > heartbeat)
                    || (lastWrite != null && now - lastWrite > heartbeat)) {
                //当lastRead, lastWrite 更新超过60S(默认) 意味着60秒没有进行通信了, 这时候发送心跳( consumer-> provider 或者 provier -> consumer 心跳是双向的)给对应的端, 检测连接是否正常
                //lastRead, lastWrite 在正常业务调用时也会跟着更新, 因此, 在业务繁忙时不发送心跳.
                Request req = new Request();
                req.setVersion(Version.getProtocolVersion());
                req.setTwoWay(true);
                req.setEvent(HEARTBEAT_EVENT);
                channel.send(req);
                if (logger.isDebugEnabled()) {
                    logger.debug("Send heartbeat to remote channel " + channel.getRemoteAddress()
                            + ", cause: The channel has no data-transmission exceeds a heartbeat period: "
                            + heartbeat + "ms");
                }
            }
        } catch (Throwable t) {
            logger.warn("Exception when heartbeat to remote channel " + channel.getRemoteAddress(), t);
        }
    }
}
