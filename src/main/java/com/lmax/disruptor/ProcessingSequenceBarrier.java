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


/**
 * {@link SequenceBarrier} handed out for gating {@link EventProcessor}s on a cursor sequence and optional dependent {@link EventProcessor}(s),
 * using the given WaitStrategy.
 */
final class ProcessingSequenceBarrier implements SequenceBarrier
{
    private final WaitStrategy waitStrategy;//等待策略。
    private final Sequence dependentSequence;//依赖的其他消费者的Sequence序列组。这个域可能指向一个序列组。
    private volatile boolean alerted = false;
    private final Sequence cursorSequence;
    private final Sequencer sequencer;//生产者

    public ProcessingSequenceBarrier(
        final Sequencer sequencer,
        final WaitStrategy waitStrategy,
        final Sequence cursorSequence,
        final Sequence[] dependentSequences)
    {
        this.sequencer = sequencer; //生产者序号控制器
        this.waitStrategy = waitStrategy; //等待策略
        this.cursorSequence = cursorSequence; //生产者序号
        if (0 == dependentSequences.length)
        {
            dependentSequence = cursorSequence;
        }
        else
        {
            dependentSequence = new FixedSequenceGroup(dependentSequences);
        }
    }

    @Override
    public long waitFor(final long sequence) //该方法不保证总是返回未处理的序号；如果有更多的可处理序号时，返回的序号也可能是超过指定序号的。
        throws AlertException, InterruptedException, TimeoutException
    {
        //先检测报警状态。
        checkAlert();
        //然后根据等待策略来等待可用的序列值。
        long availableSequence = waitStrategy.waitFor(sequence, cursorSequence, dependentSequence, this); // 通过等待策略来获取可处理事件序号，

        if (availableSequence < sequence)    // 这个方法不保证总是返回可处理的序号
        {
            return availableSequence; //如果可用的序列值小于给定的序列，那么直接返回。
        }
        //否则，要返回能安全使用的最大的序列值。
        return sequencer.getHighestPublishedSequence(sequence, availableSequence);   // 再通过生产者序号控制器返回最大的可处理序号
    }

    @Override
    public long getCursor()
    {
        return dependentSequence.get();
    }

    @Override
    public boolean isAlerted()
    {
        return alerted;
    }

    @Override
    public void alert()
    {
        alerted = true; //设置通知标记
        waitStrategy.signalAllWhenBlocking(); //如果有线程以阻塞的方式等待序列，将其唤醒。
    }

    @Override
    public void clearAlert()
    {
        alerted = false;
    }

    @Override
    public void checkAlert() throws AlertException
    {
        if (alerted)
        {
            throw AlertException.INSTANCE;
        }
    }
}