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

import java.util.UUID;

import org.springframework.data.redis.core.BoundHashOperations;
import org.springframework.data.redis.core.RedisTemplate;

@SuppressWarnings("unchecked")
public class RedisTemplateStore implements Store {

    private final BoundHashOperations<Object, Object, Object> hashOperations;

    public RedisTemplateStore(UUID sessionId, RedisTemplate<Object, Object> redisTemplate) {
        this.hashOperations = redisTemplate.boundHashOps(sessionId.toString());
    }

    @Override
    public void set(String key, Object value) {
        hashOperations.put(key, value);
    }

    @Override
    public <T> T get(String key) {
        return (T) hashOperations.get(key);
    }

    @Override
    public boolean has(String key) {
        return hashOperations.hasKey(key);
    }

    @Override
    public void del(String key) {
        hashOperations.delete(key);
    }

}
