package org.starexec.data.to;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import com.google.gson.annotations.Expose;

/**
 * Represents an SGE queue which has a collection of worker nodes that belong to it
 * @author Tyler Jensen
 */
public class Queue extends Identifiable implements Iterable<WorkerNode>, Nameable{
	@Expose private String name;
	@Expose private String status;
	@Expose private int slotsUsed;
	@Expose private int slotsReserved;
	@Expose private int slotsAvailable;
	@Expose private int slotsTotal;
	@Expose private boolean permanent;
	@Expose private boolean global_access;
	@Expose private List<WorkerNode> nodes;
	@Expose private HashMap<String, String> attributes;
	@Expose private HashMap<Integer, String[]> jobPairs;
	@Expose private int cpuTimeout;
	@Expose private int wallTimeout;
	
	public Queue() {
		this.nodes = new LinkedList<WorkerNode>();
		this.attributes = new HashMap<String, String>();
		this.jobPairs = new HashMap<Integer, String[]>();
	}
	
	/**
	 * @return the name of the queue
	 */
	public String getName() {
		return name;
	}
	/**
	 * @param name the name to set for the queue
	 */
	public void setName(String name) {
		this.name = name;
	}
	/**
	 * @return the status of the queue
	 */
	public String getStatus() {
		return status;
	}
	/**
	 * @param status the status to set for the queue
	 */
	public void setStatus(String status) {
		this.status = status;
	}
	/**
	 * @return the number of slots currently running jobs in the queue
	 */
	public int getSlotsUsed() {
		return slotsUsed;
	}

	/**
	 * @param slotsUsed number of slots currently running jobs in the queue to set
	 */
	public void setSlotsUsed(int slotsUsed) {
		this.slotsUsed = slotsUsed;
	}

	/**
	 * @return the number of slots currently reserved in the queue
	 */
	public int getSlotsReserved() {
		return slotsReserved;
	}

	/**
	 * Note this will not actually reserve slots for the queue.
	 * @param slotsReserved the number of slots currently reserved in the queue to set 
	 */
	public void setSlotsReserved(int slotsReserved) {
		this.slotsReserved = slotsReserved;
	}

	/**
	 * @return the number of slots available in the queue
	 */
	public int getSlotsAvailable() {
		return slotsAvailable;
	}

	/**
	 * @param slotsAvailable the number of slots available in the queue to set
	 */
	public void setSlotsAvailable(int slotsAvailable) {
		this.slotsAvailable = slotsAvailable;
	}

	/**
	 * @return the total number of slots in this queue
	 */
	public int getSlotsTotal() {
		return slotsTotal;
	}

	/**
	 * @param slotaTotal the total number of slots to set for this queue
	 */
	public void setSlotsTotal(int slotaTotal) {
		this.slotsTotal = slotaTotal;
	}
	
	/**
	 * @return true if the queue is permanent, false otherwise
	 */
	public boolean getPermanent() {
		return permanent;
	}

	/**
	 * @param permanent true if queue is permanent, false otherwise
	 */
	public void setPermanent(boolean permanent) {
		this.permanent = permanent;
	}
	
	/**
	 * @return true if the queue is global, false otherwise
	 */
	public boolean getGlobalAccess() {
		return global_access;
	}
	
	/**
	 * @param global_access true if queue is global, false otherwise
	 */
	public void setGlobalAccess(boolean global_access) {
		this.global_access = global_access;
	}

	/**
	 * @return the worker nodes that belong to the queue
	 */
	public List<WorkerNode> getNodes() {
		return nodes;
	}
	/**
	 * @param node the worker node to add to the queue
	 */
	public void addNode(WorkerNode node) {
		this.nodes.add(node);
	}
	
	/**
	 * @return The attributes for this worker node
	 */
	public HashMap<String, String> getAttributes() {
		return attributes;
	}

	/**
	 * @param key The key of the attribute to add
	 * @param val The value of the given attribute
	 */
	public void putAttribute(String key, String val) {
		this.attributes.put(key, val);
	}
	
	/**
	 * @return The job pairs
	 */
	public HashMap<Integer, String[]> getJobPair() {
		return jobPairs;
	}
	
	public void putJobPair(int key, String[] values) {
		this.jobPairs.put(key, values);
	}

	@Override
	public String toString() {
		return this.name;
	}

	@Override
	public Iterator<WorkerNode> iterator() {
		return this.nodes.iterator();
	}

	/**
	 * @param cpuTimeout the cpuTimeout to set
	 */
	public void setCpuTimeout(int cpuTimeout) {
		this.cpuTimeout = cpuTimeout;
	}

	/**
	 * @return the cpuTimeout
	 */
	public int getCpuTimeout() {
		return cpuTimeout;
	}

	/**
	 * @param wallTimeout the wallTimeout to set
	 */
	public void setWallTimeout(int wallTimeout) {
		this.wallTimeout = wallTimeout;
	}

	/**
	 * @return the wallTimeout
	 */
	public int getWallTimeout() {
		return wallTimeout;
	}

}
