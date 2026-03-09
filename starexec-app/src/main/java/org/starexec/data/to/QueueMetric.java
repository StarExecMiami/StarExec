package org.starexec.data.to;

public class QueueMetric {
    private long time;
    private int size;

    public QueueMetric() {}

    public QueueMetric(long time, int size) {
        this.time = time;
        this.size = size;
    }

    public long getTime() { return time; }
    public void setTime(long time) { this.time = time; }
    public int getSize() { return size; }
    public void setSize(int size) { this.size = size; }
}
