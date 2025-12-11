package org.starexec.data.to;

import org.starexec.constants.R;
import org.starexec.data.database.Queues;

public class JSTreeAttribute {
    private int id;
    private String rel;
    private boolean global;
    private int defaultQueueId;
    private int maxStages;
    // called cLass to bypass Java's class keyword. gson will lowercase the L
    private String cLass;

    public JSTreeAttribute(int id, String type, int maxStages, String cLass) {
        this.id = id;
        this.rel = type;
        this.maxStages = maxStages;
        this.cLass = cLass;
        if (type.equals("active_queue") || type.equals("inactive_queue")) {
            this.global = Queues.isQueueGlobal(id);
        }
        this.defaultQueueId = R.DEFAULT_QUEUE_ID;
    }
}
