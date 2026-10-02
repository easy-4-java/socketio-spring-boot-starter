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

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.data.redis.core.BoundHashOperations;
import org.springframework.data.redis.core.RedisTemplate;

/**
 * @author ： <a href="https://github.com/hiwepy">wandl</a>
 */
@SuppressWarnings("unchecked")
public class RedisTemplateMap<K, V> implements Map<K, V> {

    private final String name;
    private final RedisTemplate<Object, Object> redisTemplate;
    private final BoundHashOperations<Object, Object, Object> hashOperations;

    public RedisTemplateMap(RedisTemplate<Object, Object> redisTemplate, String name) {
        this.name = name;
        this.redisTemplate = redisTemplate;
        this.hashOperations = redisTemplate.boundHashOps(name);
    }

    @Override
    public int size() {
        return hashOperations.size().intValue();
    }

    @Override
    public boolean isEmpty() {
        return size() == 0;
    }

    @Override
    public boolean containsKey(Object key) {
        return hashOperations.hasKey(key);
    }

    @Override
    public boolean containsValue(Object value) {
        return hashOperations.entries().containsValue(value);
    }

    @Override
    public V get(Object key) {
        return (V) hashOperations.get(key);
    }

    @Override
    public V put(K key, V value) {
        V previous = get(key);
        hashOperations.put(key, value);
        return previous;
    }

    @Override
    public V remove(Object key) {
        Object value = hashOperations.get(key);
        hashOperations.delete(key);
        return (V) value;
    }

    @Override
    public void putAll(Map<? extends K, ? extends V> m) {
        hashOperations.putAll(m);
    }

    @Override
    public void clear() {
        redisTemplate.delete(name);
    }

    @Override
    public Set<K> keySet() {
        return (Set<K>) hashOperations.keys();
    }

    @Override
    public Collection<V> values() {
        return (Collection<V>) hashOperations.values();
    }

    @Override
    public Set<Entry<K, V>> entrySet() {
        Set<Entry<Object, Object>> sets = hashOperations.entries().entrySet();
        if (Objects.nonNull(sets)) {
            return sets.stream().map((m) -> {
                return (Entry<K, V>) m;
            }).collect(Collectors.toSet());
        }
        return Collections.emptySet();
    }

}
