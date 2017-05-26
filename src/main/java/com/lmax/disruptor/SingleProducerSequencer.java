/*
 * Copyright 2011 LMAX Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.lmax.disruptor;

import com.lmax.disruptor.util.Util;

import java.util.concurrent.locks.LockSupport;

abstract class SingleProducerSequencerPad extends AbstractSequencer //左边缓存行填充
{
    protected long p1, p2, p3, p4, p5, p6, p7;

    public SingleProducerSequencerPad(int bufferSize, WaitStrategy waitStrategy)
    {
        super(bufferSize, waitStrategy);
    }
}

abstract class SingleProducerSequencerFields extends SingleProducerSequencerPad
{
    public SingleProducerSequencerFields(int bufferSize, WaitStrategy waitStrategy)
    {
        super(bufferSize, waitStrategy);
    }

    /**
     * Set to -1 as sequence starting point
     */
    protected long nextValue = Sequence.INITIAL_VALUE; // nextValue表示生产者下一个可以使用的位置的序号,一开始是-1.
    protected long cachedValue = Sequence.INITIAL_VALUE;  // cachedValue表示上一次消费者消费数据时的位置序号,一开始是-1.
}

/**
 * <p>Coordinator for claiming sequences for access to a data structure while tracking dependent {@link Sequence}s.
 * Not safe for use from multiple threads as it does not implement any barriers.</p>
 * <p>
 * <p>Note on {@link Sequencer#getCursor()}:  With this sequencer the cursor value is updated after the call
 * to {@link Sequencer#publish(long)} is made.
 */

/**
 * 用于单生产者模式场景, 保存/追踪生产者和消费者的位置序号。
 * 由于这个类并没有实现任何的Barrier，所以在Disruptor框架中，这个类并不是线程安全的。
 * 不过由于从命名上看，就是单一生产者，所以在使用的时候也不会用多线程去调用里面的方法。
 */
public final class SingleProducerSequencer extends SingleProducerSequencerFields
{
    protected long p1, p2, p3, p4, p5, p6, p7; // 右边缓存行填充数据.

    /**
     * Construct a Sequencer with the selected wait strategy and buffer size.
     * 使用给定的bufferSize和waitStrategy创建实例.
     * @param bufferSize   the size of the buffer that this will sequence over.
     * @param waitStrategy for those waiting on sequences.
     */
    public SingleProducerSequencer(int bufferSize, final WaitStrategy waitStrategy)
    {
        super(bufferSize, waitStrategy);
    }

    /**
     * @see Sequencer#hasAvailableCapacity(int)
     */
    /**
     * 判断RingBuffer是否还有可用的空间能够容纳requiredCapacity个Event.
      当前序列的nextValue + requiredCapacity是生产者要申请的序列值。
      当前序列的cachedValue记录的是之前消费者申请的序列值。
      想一下一个环形队列，生产者在什么情况下才能申请一个序列呢？
      生产者当前的位置在消费者前面，并且不能从消费者后面追上消费者(因为是环形)，
      即 生产者要申请的序列值大于消费者之前的序列值 且 生产者要申请的序列值减去环的长度要小于消费者的序列值
      如果满足这个条件，即使不知道当前消费者的序列值，也能确保生产者可以申请给定的序列。
      如果不满足这个条件，就需要查看一下当前消费者的最小的序列值(因为可能有多个消费者)，
      如果当前要申请的序列值比当前消费者的最小序列值大了一圈(从后面追上了)，那就不能申请了(申请的话会覆盖没被消费的事件)，
      也就是说没有可用的空间(用来发布事件)了，也就是hasAvailableCapacity方法要表达的意思。
      */
    @Override
    public boolean hasAvailableCapacity(final int requiredCapacity)
    {
        long nextValue = this.nextValue; // 生产者下一个可使用的位置序号
        // 下一位置加上所需容量减去整个bufferSize，如果为正数，那证明至少转了一圈，则需要检查gatingSequences（由消费者更新里面的Sequence值）以保证不覆盖还未被消费的.
        long wrapPoint = (nextValue + requiredCapacity) - bufferSize;
        // 消费者上一次消费的位置, 消费者每次消费之后会更新该值.
        // Disruptor经常用缓存，这里缓存所有gatingSequences里最小的那个，这样不用每次都遍历一遍gatingSequences，影响效率.
        long cachedGatingSequence = this.cachedValue;
        // 先看看这个条件的对立条件: wrapPoint <= cachedGatingSequence && cachedGatingSequence <= nextValue
        // 表示当前生产者走在消费者的前面, 并且就算再申请requiredCapacity个位置达到的位置也不会覆盖消费者上一次消费的位置.
        // wrapPoint > cachedGatingSequence重叠位置大于缓存的消费者处理的序号，说明有消费者没有处理完成，不能够放置数据
        // cachedGatingSequence > nextValue只会在 https://github.com/LMAX-Exchange/disruptor/issues/76 情况下存在
        if (wrapPoint > cachedGatingSequence || cachedGatingSequence > nextValue)
        {
            cursor.setVolatile(nextValue);  // StoreLoad fence
            // gatingSequences保存的是消费者的当前消费位置, 因为可能有多个消费者, 所以此处获取序号最小的位置.
            long minSequence = Util.getMinimumSequence(gatingSequences, nextValue);
            this.cachedValue = minSequence;  // 更新消费者上一次消费的位置

            if (wrapPoint > minSequence)
            {
                return false;
            }
        }

        return true;
    }

    /**
     * @see Sequencer#next()
     */
    @Override
    public long next()
    {
        return next(1);
    }

    /**
     * @see Sequencer#next(int) 申请n个可用空间, 返回该位置的序号, 如果当前没有可用空间, 则一直阻塞直到有可用空间位置.
     */
    @Override
    public long next(int n)
    {
        if (n < 1)
        {
            throw new IllegalArgumentException("n must be > 0");
        }

        long nextValue = this.nextValue; //当前生产者发布的的最大序列

        long nextSequence = nextValue + n; //要发布的最大序列
        long wrapPoint = nextSequence - bufferSize; //覆盖点
        long cachedGatingSequence = this.cachedValue; //消费者中处理序列最小的前一个序列
        // 从逻辑来看，当生产者想申请某一个序列时，需要保证不会绕一圈之后，对消费者追尾；同时需要保证消费者上一次的消费最小序列没有对生产者追尾。
        // next方法是真正申请序列的方法，里面的逻辑和hasAvailableCapacity一样，只是在不能申请序列的时候会阻塞等待一下，然后重试。
        if (wrapPoint > cachedGatingSequence || cachedGatingSequence > nextValue)
        {
            cursor.setVolatile(nextValue);  // StoreLoad fence

            long minSequence;
            //如果一直没有可用空间, 当前线程挂起, 不断循环检测，直到有可用空间。
            //循环判断生产者绕一圈之后如果还是追尾，则等待1纳秒，目前就是简单的等待，看注释是想在以后通过waitStrategy来等待
            while (wrapPoint > (minSequence = Util.getMinimumSequence(gatingSequences, nextValue))) //等待直到有可用的缓存
            {
                waitStrategy.signalAllWhenBlocking(); //触发一次等待策略
                LockSupport.parkNanos(1L); // TODO: Use waitStrategy to spin?
            }

            this.cachedValue = minSequence; //循环退出后，将获取的消费者最小序列，赋值给cachedValue
        }

        this.nextValue = nextSequence; //更新当前生产者发布的的最大序列

        return nextSequence; // 返回最后一个可用位置的序号.
    }

    /**
     * @see Sequencer#tryNext()
     */
    @Override
    public long tryNext() throws InsufficientCapacityException
    {
        return tryNext(1);
    }

    /**
     * @see Sequencer#tryNext(int) 尝试申请n个可用空间,如果没有,抛出异常.
     */
    @Override
    public long tryNext(int n) throws InsufficientCapacityException
    {
        if (n < 1)
        {
            throw new IllegalArgumentException("n must be > 0");
        }

        // 先调用hasAvailableCapacity函数判断是否能分配, 不能直接抛出异常.
        if (!hasAvailableCapacity(n))
        {
            throw InsufficientCapacityException.INSTANCE;
        }

        long nextSequence = this.nextValue += n;

        return nextSequence;
    }

    /**
     * @see Sequencer#remainingCapacity() 可用位置数目=环形队列的容量减去生产者与消费者的序列差
     */
    @Override
    public long remainingCapacity()
    {
        long nextValue = this.nextValue;

        long consumed = Util.getMinimumSequence(gatingSequences, nextValue); // (多个)消费者消费的最小位置
        long produced = nextValue;// 生产者的位置
        return getBufferSize() - (produced - consumed);
    }

    /**
     * @see Sequencer#claim(long) 更改生产者的位置序号.claim方法是声明一个序列，在初始化的时候用。
     */
    @Override
    public void claim(long sequence)
    {
        this.nextValue = sequence;
    }

    /**
     * @see Sequencer#publish(long) 发布sequence位置的Event
     */
    @Override
    public void publish(long sequence)
    {
        cursor.set(sequence);  //cursor代表可以消费的sequence,更新生产者游标
        waitStrategy.signalAllWhenBlocking();  //通知所有消费者,数据可以被消费了
    }

    /**
     * @see Sequencer#publish(long, long) 发布这个区间内的Event.
     */
    @Override
    public void publish(long lo, long hi)
    {
        publish(hi);
    }

    /**
     * @see Sequencer#isAvailable(long) 判断sequence位置的数据是否已经发布并且可以被消费.
     */
    @Override
    public boolean isAvailable(long sequence)
    {
        return sequence <= cursor.get();
    }

    @Override
    public long getHighestPublishedSequence(long lowerBound, long availableSequence)
    {
        return availableSequence;
    }
}
