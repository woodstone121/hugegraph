/*
 * Copyright 2017 HugeGraph Authors
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.baidu.hugegraph.backend.cache;

import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.baidu.hugegraph.backend.id.Id;
import com.baidu.hugegraph.concurrent.KeyLock;

public class RamCache implements Cache {

    public static final int MB = 1024 * 1024;
    public static final int DEFAULT_SIZE = 1 * MB;
    public static final int MAX_INIT_CAP = 100 * MB;

    private static final Logger logger = LoggerFactory.getLogger(Cache.class);

    private volatile long hits = 0L;
    private volatile long miss = 0L;

    // Default expire time(ms)
    private volatile long expire = 0L;

    private final KeyLock keyLock;
    private final int capacity;

    // Implement LRU cache
    private final Map<Id, LinkNode<Id, Object>> map;
    private final LinkedQueueNonBigLock<Id, Object> queue;

    public RamCache() {
        this(DEFAULT_SIZE);
    }

    // NOTE: count in number of items, not in bytes
    public RamCache(int capacity) {
        this.keyLock = new KeyLock();

        if (capacity < 8) {
            capacity = 8;
        }
        this.capacity = capacity;

        int initialCapacity = capacity >> 3;
        if (initialCapacity > MAX_INIT_CAP) {
            initialCapacity = MAX_INIT_CAP;
        }

        this.map = new ConcurrentHashMap<>(initialCapacity);
        this.queue = new LinkedQueueNonBigLock<>();
    }

    private Object access(Id id) {
        assert id != null;

        this.keyLock.lock(id);
        try {
            LinkNode<Id, Object> node = this.map.get(id);
            if (node == null) {
                return null;
            }

            // Move the node from mid to tail
            if (this.queue.remove(node) == null) {
                // The node may be removed by others through dequeue()
                return null;
            }
            /*
             * FIXME: currently we use enqueue() with copy the node instead of
             * enqueue(node) with the node itself due to the node may lead to
             * dead lock. In the end we will use the sorted lock instead.
             */
            this.map.put(id, this.queue.enqueue(node.key(), node.value()));

            // Ignore concurrent write for hits
            logger.debug("RamCache cached '{}' (hits={}, miss={})",
                         id, ++this.hits, this.miss);

            assert id.equals(node.key());
            return node.value();
        } finally {
            this.keyLock.unlock(id);
        }
    }

    private void write(Id id, Object value) {
        assert id != null;
        assert this.capacity > 0;

        this.keyLock.lock(id);
        try {
            // The cache is full
            while (this.map.size() >= this.capacity) {
                /*
                 * Remove the oldest from the queue
                 * NOTE: it maybe return null if someone else (that's other
                 * threads) are doing dequeue() and the queue may be empty.
                 */
                LinkNode<Id, Object> removed = this.queue.dequeue();
                if (removed == null) {
                    /*
                     * If at this time someone add some new items, these will
                     * be cleared in the map, but still stay in the queue, so
                     * the queue will have some more nodes than the map.
                     */
                    this.map.clear();
                    break;
                }
                /*
                 * Remove the oldest from the map
                 * NOTE: it maybe return null if other threads are doing remove
                 */
                this.map.remove(removed.key());
                logger.debug("RamCache replaced '{}' with '{}' (capacity={})",
                             removed.key(), id, this.capacity);
                /*
                 * Release the object
                 * NOTE: we can't reuse the removed node due to someone else
                 * may access the node (will do remove() -> enqueue())
                 */
                removed = null;
            }

            LinkNode<Id, Object> node = this.map.get(id);
            if (node != null) {
                /*
                 * Update the old value in the node if the id exists
                 * FIXME: update the node position in queue(should remove it?)
                 */
                node.value(value);
            } else {
                // Add the new item to tail of the queue, then map it
                assert !this.map.containsKey(id);
                this.map.put(id, this.queue.enqueue(id, value));
            }
        } finally {
            this.keyLock.unlock(id);
        }
    }

    private void remove(Id id) {
        assert id != null;

        this.keyLock.lock(id);
        try {
            /*
             * Remove the id from map and queue
             * NOTE: it maybe return null if other threads have removed the id
             */
            LinkNode<Id, Object> node = this.map.remove(id);
            if (node != null) {
                this.queue.remove(node);
            }
        } finally {
            this.keyLock.unlock(id);
        }
    }

    @Override
    public Object get(Id id) {
        Object value = null;
        if (this.map.containsKey(id)) {
            // Maybe the id removed by other threads and returned null value
            value = this.access(id);
        }
        if (value == null) {
            logger.debug("RamCache missed '{}' (miss={}, hits={})",
                         id, ++this.miss, this.hits);
        }
        return value;
    }

    @Override
    public Object getOrFetch(Id id, Function<Id, Object> fetcher) {
        Object value = null;
        if (this.map.containsKey(id)) {
            // Maybe the id removed by other threads and returned null value
            value = this.access(id);
        }
        if (value == null) {
            logger.debug("RamCache missed '{}' (miss={}, hits={})",
                         id, ++this.miss, this.hits);
            // Do fetch and update the cache
            value = fetcher.apply(id);
            this.update(id, value);
        }
        return value;
    }

    @Override
    public void update(Id id, Object value) {
        if (id == null || value == null ||
            this.capacity <= 0) {
            return;
        }
        this.write(id, value);
    }

    @Override
    public void updateIfAbsent(Id id, Object value) {
        if (id == null || value == null ||
            this.capacity <= 0 ||
            this.map.containsKey(id)) {
            return;
        }
        this.write(id, value);
    }

    @Override
    public void invalidate(Id id) {
        if (id == null || !this.map.containsKey(id)) {
            return;
        }
        this.remove(id);
    }

    @Override
    public void clear() {
        // TODO: synchronized
        this.map.clear();
        this.queue.clear();
    }

    @Override
    public void expire(long seconds) {
        // Convert the unit from seconds to milliseconds
        this.expire = seconds * 1000;
    }

    @Override
    public void tick() {
        if (this.expire <= 0) {
            return;
        }

        long current = now();
        List<Id> expireItems = new LinkedList<>();
        for (LinkNode<Id, Object> node : this.map.values()) {
            if (current - node.time() > this.expire) {
                expireItems.add(node.key());
            }
        }

        logger.debug("Cache expire items: {} (expire {}ms)",
                     expireItems.size(), this.expire);
        for (Id id : expireItems) {
            this.remove(id);
        }
        logger.debug("Cache expired items: {} (size {})",
                     expireItems.size(), size());
    }

    @Override
    public long capacity() {
        return this.capacity;
    }

    @Override
    public long size() {
        return this.map.size();
    }

    @Override
    public String toString() {
        return this.map.toString();
    }

    private static final long now() {
        return System.currentTimeMillis();
    }

    private static class LinkNode<K, V> {

        private final K key;
        private V value;
        private long time;
        private LinkNode<K, V> prev;
        private LinkNode<K, V> next;

        public LinkNode(K key, V value) {
            this.time = now();
            this.key = key;
            this.value = value;
            this.prev = this.next = null;
        }

        public void value(V value) {
            this.value = value;
        }

        public final K key() {
            return this.key;
        }

        public final V value() {
            return this.value;
        }

        public long time() {
            return this.time;
        }

        @Override
        public String toString() {
            return this.value == null ? null : this.value.toString();
        }
    }

    private static class LinkedQueueNonBigLock<K, V> {

        private final LinkNode<K, V> empty;
        private final LinkNode<K, V> head;
        private LinkNode<K, V> rear;

        @SuppressWarnings("unchecked")
        public LinkedQueueNonBigLock() {
            this.empty = new LinkNode<>(null, (V) "<empty>");
            this.head = new LinkNode<>(null, (V) "<head>");
            this.rear = new LinkNode<>(null, (V) "<rear>");

            this.reset();
        }

        /**
         * Reset the head node and rear node
         * NOTE:
         *  only called by LinkedQueueNonBigLock() without lock,
         *  or called by clear() with lock(head, rear)
         */
        private void reset() {
            this.head.prev = this.empty;
            this.head.next = this.rear;

            this.rear.prev = this.head;
            this.rear.next = this.empty;

            assert this.head.next == this.rear;
            assert this.rear.prev == this.head;
        }

        /**
         * Dump keys of all nodes in this queue (just for debug)
         */
        private List<K> dumpKeys() {
            List<K> keys = new LinkedList<>();
            LinkNode<K, V> node = this.head.next;
            while (node != this.rear && node != this.empty) {
                assert node != null;
                keys.add(node.key());
                node = node.next;
            }
            return keys;
        }

        /**
         * Check whether a key not in this queue (just for debug)
         */
        private boolean checkNotInQueue(K key) {
            List<K> keys = this.dumpKeys();
            if (keys.contains(key)) {
                throw new RuntimeException(String.format(
                          "Expect %s should be not in %s", key, keys));
            }
            return true;
        }

        /**
         * Check whether there is circular reference (just for debug)
         * NOTE: but it is important to note that this is only key check
         * rather than pointer check.
         */
        private boolean checkPrevNotInNext(LinkNode<K, V> self) {
            LinkNode<K, V> prev = self.prev;
            if (prev.key() == null) {
                assert prev == this.head || prev == this.empty : prev;
                return true;
            }
            List<K> keys = this.dumpKeys();
            int prevPos = keys.indexOf(prev.key());
            int selfPos = keys.indexOf(self.key());
            if (prevPos > selfPos && selfPos != -1) {
                throw new RuntimeException(String.format(
                          "Expect %s should be before %s, actual %s",
                          prev.key(), self.key(), keys));
            }
            return true;
        }

        public void clear() {
            synchronized (this.rear) {
                assert this.rear.prev != null : this.head.next;
                while (true) {
                    /*
                     * If someone is removing the last node by remove(),
                     * it will update the rear.prev, so we should lock it.
                     */
                    LinkNode<K, V> last = this.rear.prev;
                    synchronized (last) {
                        if (last != this.rear.prev) {
                            // The rear.prev has changed, try to get lock again
                            continue;
                        }
                        synchronized (this.head) {
                            this.reset();
                        }
                        return;
                    }
                }
            }
        }

        public final LinkNode<K, V> enqueue(K key, V value) {
            return this.enqueue(new LinkNode<>(key, value));
        }

        public final LinkNode<K, V> enqueue(LinkNode<K, V> node) {
            assert node != null;
            // TODO: should we lock the new `node`?
            synchronized (this.rear) {
                while (true) {
                    LinkNode<K, V> last = this.rear.prev;
                    synchronized (last) {
                        if (last != this.rear.prev) {
                            // The rear.prev has changed, try to get lock again
                            continue;
                        }
                        assert this.checkNotInQueue(node.key());
                        /*
                         * Link the node to the rear before to the last if we
                         * have not locked the node itself, because dumpKeys()
                         * may get the new node with next=null.
                         * TODO: it also depends on memory barrier.
                         */
                        // Build the link between `node` and the rear
                        node.next = this.rear;
                        assert this.rear.prev == last : this.rear.prev;
                        this.rear.prev = node;
                        // Build the link between `last` and `node`
                        node.prev = last;
                        last.next = node;

                        return node;
                    }
                }
            }
        }

        public final LinkNode<K, V> dequeue() {
            while (true) {
                LinkNode<K, V> first = this.head.next;
                synchronized (first) {
                    if (first != this.head.next) {
                        // The head.next has changed, try to get lock again
                        continue;
                    }
                    if (first == this.rear) {
                        // Empty queue
                        return null;
                    }
                    synchronized (this.head) {
                        // Break the link between the head and `first`
                        assert first.next != null;
                        this.head.next = first.next;
                        first.next.prev = this.head;

                        // Clear the links of the first node
                        first.prev = this.empty;
                        first.next = this.empty;
                    }
                    return first;
                }
            }
        }

        public final LinkNode<K, V> remove(LinkNode<K, V> node) {
            synchronized (node) {
                assert node != this.empty;
                assert node != this.head && node != this.rear;

                while (true) {
                    LinkNode<K, V> prev = node.prev;
                    if (prev == this.empty) {
                        assert node.next == this.empty;
                        // Ignore the node if it has been removed
                        return null;
                    }

                    // We can use the assertion to debug circular reference:
                    // assert this.checkPrevNotInNext(node);

                    synchronized (prev) {
                        if (prev != node.prev) {
                            /*
                             * The previous node has changed (maybe it's lock
                             * released after it's removed, then we got the
                             * lock), so try again until it's not changed.
                             */
                            continue;
                        }
                        assert node.next != null : node;
                        assert node.next != node.prev : node.next;

                        // Build the link between node.prev and node.next
                        node.prev.next = node.next;
                        node.next.prev = node.prev;

                        // Assert to debug the queue state
                        assert this.checkPrevNotInNext(node.next);
                        assert prev == node.prev : prev.key + "!=" + node.prev;

                        // Clear the links of `node`
                        node.prev = this.empty;
                        node.next = this.empty;

                        return node;
                    }
                }
            }
        }
    }
}