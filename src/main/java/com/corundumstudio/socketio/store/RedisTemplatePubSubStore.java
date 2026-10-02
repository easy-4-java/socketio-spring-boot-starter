/*
 * Copyright (c) 2018, hiwepy (https://github.com/hiwepy).
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.corundumstudio.socketio.store;

import java.util.Queue;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;

import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import com.corundumstudio.socketio.store.pubsub.PubSubListener;
import com.corundumstudio.socketio.store.pubsub.PubSubMessage;
import com.corundumstudio.socketio.store.pubsub.PubSubStore;
import com.corundumstudio.socketio.store.pubsub.PubSubType;

import io.netty.util.internal.PlatformDependent;

public class RedisTemplatePubSubStore implements PubSubStore {

    private final RedisTemplate<Object, Object> redisTemplate;
    private final RedisMessageListenerContainer listenerContainer;
    private final Long nodeId;

    private final ConcurrentMap<String, Queue<MessageListener>> map = PlatformDependent.newConcurrentHashMap();

    public RedisTemplatePubSubStore(RedisTemplate<Object, Object> redisTemplate, RedisMessageListenerContainer listenerContainer, Long nodeId) {
        this.redisTemplate = redisTemplate;
        this.listenerContainer = listenerContainer;
        this.nodeId = nodeId;
    }

    @Override
    public void publish(PubSubType type, PubSubMessage msg) {
        msg.setNodeId(nodeId);
        redisTemplate.convertAndSend(type.toString(), msg);
    }

    @Override
    public synchronized <T extends PubSubMessage> void subscribe(PubSubType type, final PubSubListener<T> listener, Class<T> clazz) {
        String name = type.toString();
        MessageListener msgListener = new MessageListener() {

            @SuppressWarnings("unchecked")
            @Override
            public void onMessage(Message message, byte[] pattern) {

                byte[] body = message.getBody();
                PubSubMessage msg = (PubSubMessage) redisTemplate.getValueSerializer().deserialize(body);
                if (Objects.nonNull(msg) && clazz.isInstance(msg) && !nodeId.equals(msg.getNodeId())) {
                    listener.onMessage((T) msg);
                }
            }

        };
        listenerContainer.addMessageListener(msgListener, new ChannelTopic(name));
        Queue<MessageListener> list = map.get(name);
        if (Objects.isNull(list)) {
            list = new ConcurrentLinkedQueue<MessageListener>();
            Queue<MessageListener> oldList = map.putIfAbsent(name, list);
            if (Objects.nonNull(oldList)) {
                list = oldList;
            }
        }
        list.add(msgListener);
    }

    @Override
    public synchronized void unsubscribe(PubSubType type) {
        String name = type.toString();
        Queue<MessageListener> regListeners = map.remove(name);
        if (Objects.isNull(regListeners)) {
            return;
        }
        for (MessageListener listener : regListeners) {
             listenerContainer.removeMessageListener(listener);
        }
    }

    @Override
    public synchronized void shutdown() {
        for (Queue<MessageListener> listeners : map.values()) {
            for (MessageListener listener : listeners) {
                listenerContainer.removeMessageListener(listener);
            }
        }
        map.clear();
    }

}
