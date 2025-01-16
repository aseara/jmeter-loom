//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by FernFlower decompiler)
//

package com.example.jmeter.plugin;

import org.apache.jmeter.engine.StandardJMeterEngine;
import org.apache.jmeter.testelement.schema.PropertiesAccessor;
import org.apache.jmeter.threads.AbstractThreadGroup;
import org.apache.jmeter.threads.JMeterContext;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.JMeterThread;
import org.apache.jmeter.threads.JMeterVariables;
import org.apache.jmeter.threads.ListenerNotifier;

import org.apache.jmeter.threads.ThreadGroupSchema;
import org.apache.jorphan.collections.ListedHashTree;
import org.apache.jorphan.util.JMeterStopTestException;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serial;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

public class CustomThreadGroup extends AbstractThreadGroup {
    @Serial
    private static final long serialVersionUID = 282L;
    private static final Logger log = LoggerFactory.getLogger(CustomThreadGroup.class);
    private static final long WAIT_TO_DIE;
    public static final String RAMP_TIME = "ThreadGroup.ramp_time";
    public static final String SCHEDULER = "ThreadGroup.scheduler";
    public static final String DURATION = "ThreadGroup.duration";
    public static final String DELAY = "ThreadGroup.delay";
    private final ConcurrentHashMap<JMeterThread, Thread> allThreads = new ConcurrentHashMap<>();
    private final transient ReentrantLock addThreadLock = new ReentrantLock();
    private volatile boolean running = false;
    private int groupNumber;
    private ListenerNotifier notifier;
    private ListedHashTree threadGroupTree;

    public CustomThreadGroup() {
    }

    @NotNull
    public ThreadGroupSchema getSchema() {
        return ThreadGroupSchema.INSTANCE;
    }

    public @NotNull PropertiesAccessor<? extends CustomThreadGroup, ? extends ThreadGroupSchema> getProps() {
        return new PropertiesAccessor<>(this, this.getSchema());
    }

    public boolean getScheduler() {
        return this.getPropertyAsBoolean(SCHEDULER);
    }

    public long getDuration() {
        return this.getPropertyAsLong(DURATION);
    }

    public long getDelay() {
        return this.getPropertyAsLong(DELAY);
    }

    public int getRampUp() {
        return this.getPropertyAsInt(RAMP_TIME);
    }

    private void scheduleThread(JMeterThread thread, long now) {
        if (this.getScheduler()) {
            if (this.getDelay() >= 0L) {
                thread.setStartTime(this.getDelay() * 1000L + now);
                if (this.getDuration() > 0L) {
                    thread.setEndTime(this.getDuration() * 1000L + thread.getStartTime());
                    thread.setScheduled(true);
                } else {
                    throw new JMeterStopTestException("Invalid duration " + this.getDuration() + " set in Thread Group:" + this.getName());
                }
            } else {
                throw new JMeterStopTestException("Invalid delay " + this.getDelay() + " set in Thread Group:" + this.getName());
            }
        }
    }

    public void start(int groupNum, ListenerNotifier notifier, ListedHashTree threadGroupTree, StandardJMeterEngine engine) {
        this.running = true;
        this.groupNumber = groupNum;
        this.notifier = notifier;
        this.threadGroupTree = threadGroupTree;
        int numThreads = this.getNumThreads();
        int rampUpPeriodInSeconds = this.getRampUp();
        log.info("Starting thread group... number={} threads={} ramp-up={}", this.groupNumber, numThreads, rampUpPeriodInSeconds);

        JMeterVariables variables = JMeterContextService.getContext().getVariables();
        long lastThreadStartInMillis = 0L;
        int delayForNextThreadInMillis = 0;
        int perThreadDelayInMillis = Math.round((float)rampUpPeriodInSeconds * 1000.0F / (float)numThreads);

        for(int threadNum = 0; this.running && threadNum < numThreads; ++threadNum) {
            long nowInMillis = System.currentTimeMillis();
            if (threadNum > 0) {
                long timeElapsedToStartLastThread = nowInMillis - lastThreadStartInMillis;
                delayForNextThreadInMillis = (int)((long)delayForNextThreadInMillis + ((long)perThreadDelayInMillis - timeElapsedToStartLastThread));
            }

            if (log.isDebugEnabled()) {
                log.debug("Computed delayForNextThreadInMillis:{} for thread:{}", delayForNextThreadInMillis, Thread.currentThread().threadId());
            }

            lastThreadStartInMillis = nowInMillis;
            this.startNewThread(notifier, threadGroupTree, engine, threadNum, variables, nowInMillis, Math.max(0, delayForNextThreadInMillis));
        }

        log.info("Started thread group number {}", this.groupNumber);
    }

    private JMeterThread startNewThread(ListenerNotifier notifier, ListedHashTree threadGroupTree, StandardJMeterEngine engine, int threadNum, JMeterVariables variables, long now, int delay) {
        JMeterThread jmThread = this.makeThread(engine, this, notifier, this.groupNumber, threadNum, cloneTree(threadGroupTree), variables);
        this.scheduleThread(jmThread, now);
        jmThread.setInitialDelay(delay);
        Thread newThread = Thread.ofVirtual().unstarted(jmThread);
        newThread.setName(jmThread.getThreadName());
        this.registerStartedThread(jmThread, newThread);
        newThread.start();
        return jmThread;
    }

    @Serial
    private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
        in.defaultReadObject();
    }

    private void registerStartedThread(JMeterThread jMeterThread, Thread newThread) {
        this.allThreads.put(jMeterThread, newThread);
    }

    public JMeterThread addNewThread(int delay, StandardJMeterEngine engine) {
        long now = System.currentTimeMillis();
        JMeterContext context = JMeterContextService.getContext();
        int numThreads;
        try {
            addThreadLock.lock();
            numThreads = this.getNumThreads();
            this.setNumThreads(numThreads + 1);
        } finally {
            addThreadLock.unlock();
        }

        JMeterThread newJmThread = this.startNewThread(this.notifier, this.threadGroupTree, engine, numThreads, context.getVariables(), now, delay);
        JMeterContextService.addTotalThreads(1);
        log.info("Started new thread in group {}", this.groupNumber);
        return newJmThread;
    }

    public boolean stopThread(String threadName, boolean now) {
        final boolean[] stopped = {false};
        this.allThreads.forEach((jmThread, jvmThread) -> {
            if (jmThread.getThreadName().equals(threadName)) {
                stopThread(jmThread, jvmThread, now);
                stopped[0] = true;
            }
        });
        return stopped[0];
    }

    private static void stopThread(JMeterThread jmeterThread, Thread jvmThread, boolean interrupt) {
        jmeterThread.stop();
        jmeterThread.interrupt();
        if (interrupt && jvmThread != null) {
            jvmThread.interrupt();
        }

    }

    public void threadFinished(JMeterThread thread) {
        if (log.isDebugEnabled()) {
            log.debug("Ending thread {}", thread.getThreadName());
        }
        this.allThreads.remove(thread);
    }

    public void tellThreadsToStop(boolean now) {
        this.running = false;
        this.allThreads.forEach((key, value) -> stopThread(key, value, now));
    }

    public void tellThreadsToStop() {
        this.tellThreadsToStop(true);
    }

    public void stop() {
        this.running = false;
        this.allThreads.keySet().forEach(JMeterThread::stop);
    }

    public int numberOfActiveThreads() {
        return this.allThreads.size();
    }

    public boolean verifyThreadsStopped() {
        boolean stoppedAll = true;
        for (Thread tt : this.allThreads.values()) {
            if (!verifyThreadStopped(tt)) {
                stoppedAll = false;
                break;
            }
        }
        return stoppedAll;
    }

    private static boolean verifyThreadStopped(Thread thread) {
        boolean stopped = true;
        if (thread != null && thread.isAlive()) {
            try {
                thread.join(WAIT_TO_DIE);
            } catch (InterruptedException var3) {
                Thread.currentThread().interrupt();
            }

            if (thread.isAlive()) {
                stopped = false;
                if (log.isWarnEnabled()) {
                    log.warn("Thread won't exit: {}", thread.getName());
                }
            }
        }

        return stopped;
    }

    public void waitThreadsStopped() {
        while(!this.allThreads.isEmpty()) {
            this.allThreads.values().forEach(CustomThreadGroup::waitThreadStopped);
        }

    }

    private static void waitThreadStopped(Thread thread) {
        if (thread != null) {
            while(thread.isAlive()) {
                try {
                    thread.join(WAIT_TO_DIE);
                } catch (InterruptedException var2) {
                    Thread.currentThread().interrupt();
                }
            }

        }
    }

    static {
        WAIT_TO_DIE = DEFAULT_THREAD_STOP_TIMEOUT.toMillis();
    }
}
