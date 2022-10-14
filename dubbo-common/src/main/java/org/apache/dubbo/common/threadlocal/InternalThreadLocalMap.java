/*
 * Copyright 2014 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package org.apache.dubbo.common.threadlocal;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The internal data structure that stores the threadLocal variables for Netty and all {@link InternalThread}s.
 * Note that this class is for internal use only. Use {@link InternalThread}
 * unless you know what you are doing.
 */
public final class InternalThreadLocalMap {

    private Object[] indexedVariables;

    private static ThreadLocal<InternalThreadLocalMap> slowThreadLocalMap = new ThreadLocal<InternalThreadLocalMap>();

    private static final AtomicInteger NEXT_INDEX = new AtomicInteger();

    static final Object UNSET = new Object();

    // Reference: https://hg.openjdk.java.net/jdk8/jdk8/jdk/file/tip/src/share/classes/java/util/ArrayList.java#l229
    private static final int ARRAY_LIST_CAPACITY_MAX_SIZE = Integer.MAX_VALUE - 8;

    private static final int ARRAY_LIST_CAPACITY_EXPAND_THRESHOLD = 1 << 30;

    public static InternalThreadLocalMap getIfSet() {
        Thread thread = Thread.currentThread();
        if (thread instanceof InternalThread) {
            return ((InternalThread) thread).threadLocalMap();
        }
        return slowThreadLocalMap.get();
    }

    public static InternalThreadLocalMap get() {
        Thread thread = Thread.currentThread();
        if (thread instanceof InternalThread) {
            //internalThread继承Thread其内部持有一个internalThreadLocalMap, 每个ThreadLocalMap 包含一个数组容器和一个ThreadLocal容器
            //internalThreadLocal实例分别持有一个自己数据保存在 internalThreadLocalMap中的数组容器 中的index.
            //internalThread数据获取时通过 internalThreadLocal中的index 到当前线程持有的internalThreadLocalMap 中的数组容器中获取数据

            //通过每个internalThreadLocal持有其数据在数组中保存的位置避免了 jdk ThreadLocal写入时hash冲突导致的性能问题 (线性探测法)
            return fastGet((InternalThread) thread);
        }
        //非internalThread底层使用ThreadLocal获取数据
        return slowGet();
    }

    public static InternalThreadLocalMap getAndRemove() {
        try {
            Thread thread = Thread.currentThread();
            if (thread instanceof InternalThread) {
                return ((InternalThread) thread).threadLocalMap();
            }
            return slowThreadLocalMap.get();
        } finally {
            remove();
        }
    }

    public static void set(InternalThreadLocalMap internalThreadLocalMap) {
        Thread thread = Thread.currentThread();
        if (thread instanceof InternalThread) {
            ((InternalThread) thread).setThreadLocalMap(internalThreadLocalMap);
        }
        slowThreadLocalMap.set(internalThreadLocalMap);
    }

    public static void remove() {
        Thread thread = Thread.currentThread();
        if (thread instanceof InternalThread) {
            ((InternalThread) thread).setThreadLocalMap(null);
        } else {
            slowThreadLocalMap.remove();
        }
    }

    public static void destroy() {
        slowThreadLocalMap = null;
    }

    public static int nextVariableIndex() {
        int index = NEXT_INDEX.getAndIncrement();
        if (index >= ARRAY_LIST_CAPACITY_MAX_SIZE || index < 0) {
            NEXT_INDEX.set(ARRAY_LIST_CAPACITY_MAX_SIZE);
            throw new IllegalStateException("Too many thread-local indexed variables");
        }
        return index;
    }

    public static int lastVariableIndex() {
        return NEXT_INDEX.get() - 1;
    }

    private InternalThreadLocalMap() {
        //初始化一个数组并给每个元素赋值一个默认值
        //初始化数组时需要结合全局计数器 NEXT_INDEX 计算数组长度,
        //数组中index < NEXT_INDEX 的位置都被赋默认值后续不使用????
        indexedVariables = newIndexedVariableTable();
    }

    public Object indexedVariable(int index) {
        Object[] lookup = indexedVariables;
        return index < lookup.length ? lookup[index] : UNSET;
    }

    /**
     * @return {@code true} if and only if a new thread-local variable has been created
     */
    public boolean setIndexedVariable(int index, Object value) {
        Object[] lookup = indexedVariables;
        if (index < lookup.length) {
            Object oldValue = lookup[index];
            lookup[index] = value;
            return oldValue == UNSET;
        } else {
            //超出现有数组容量时数组扩容
            expandIndexedVariableTableAndSet(index, value);
            return true;
        }
    }

    public Object removeIndexedVariable(int index) {
        Object[] lookup = indexedVariables;
        if (index < lookup.length) {
            Object v = lookup[index];
            lookup[index] = UNSET;
            return v;
        } else {
            return UNSET;
        }
    }

    public int size() {
        int count = 0;
        for (Object o : indexedVariables) {
            if (o != UNSET) {
                ++count;
            }
        }

        //the fist element in `indexedVariables` is a set to keep all the InternalThreadLocal to remove
        //look at method `addToVariablesToRemove`
        return count - 1;
    }

    private static Object[] newIndexedVariableTable() {
        int variableIndex = NEXT_INDEX.get();
        int newCapacity = variableIndex < 32 ? 32 : newCapacity(variableIndex);
        Object[] array = new Object[newCapacity];
        Arrays.fill(array, UNSET);
        return array;
    }

    private static int newCapacity(int index) {
        int newCapacity;
        if (index < ARRAY_LIST_CAPACITY_EXPAND_THRESHOLD) {
            newCapacity = index;
            newCapacity |= newCapacity >>>  1;
            newCapacity |= newCapacity >>>  2;
            newCapacity |= newCapacity >>>  4;
            newCapacity |= newCapacity >>>  8;
            newCapacity |= newCapacity >>> 16;
            newCapacity ++;
        } else {
            newCapacity = ARRAY_LIST_CAPACITY_MAX_SIZE;
        }
        return newCapacity;
    }

    private static InternalThreadLocalMap fastGet(InternalThread thread) {
        //获取当前线程持有的 internalThreadLocalMap, 如果没有就创建一个
        InternalThreadLocalMap threadLocalMap = thread.threadLocalMap();
        if (threadLocalMap == null) {
            thread.setThreadLocalMap(threadLocalMap = new InternalThreadLocalMap());
        }
        return threadLocalMap;
    }

    private static InternalThreadLocalMap slowGet() {
        ThreadLocal<InternalThreadLocalMap> slowThreadLocalMap = InternalThreadLocalMap.slowThreadLocalMap;
        InternalThreadLocalMap ret = slowThreadLocalMap.get();
        if (ret == null) {
            ret = new InternalThreadLocalMap();
            slowThreadLocalMap.set(ret);
        }
        return ret;
    }

    private void expandIndexedVariableTableAndSet(int index, Object value) {
        Object[] oldArray = indexedVariables;
        final int oldCapacity = oldArray.length;
        int newCapacity = newCapacity(index);
        Object[] newArray = Arrays.copyOf(oldArray, newCapacity);
        Arrays.fill(newArray, oldCapacity, newArray.length, UNSET);
        newArray[index] = value;
        indexedVariables = newArray;
    }
}
