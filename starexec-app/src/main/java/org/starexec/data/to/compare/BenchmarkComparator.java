package org.starexec.data.to.compare;

import org.starexec.data.to.Benchmark;

import java.util.Comparator;

public class BenchmarkComparator implements Comparator<Benchmark> {
	private final int column;
	private final boolean asc;

	public BenchmarkComparator(int c, boolean a) {
		column = c;
		asc = a;
	}

	/**
	 * 0 name 1 type
	 */
	@Override
	public int compare(Benchmark o1, Benchmark o2) {
		if (!asc) {
			Benchmark temp = o1;
			o1 = o2;
			o2 = temp;
		}
		if (column == 1) {
			String name1 = (o1.getType() != null && o1.getType().getName() != null) ? o1.getType().getName() : "none";
			String name2 = (o2.getType() != null && o2.getType().getName() != null) ? o2.getType().getName() : "none";
			return name1.compareToIgnoreCase(name2);
		}
		return o1.getName().compareToIgnoreCase(o2.getName());
	}
}
