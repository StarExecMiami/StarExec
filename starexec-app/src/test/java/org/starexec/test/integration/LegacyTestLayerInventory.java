package org.starexec.test.integration;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Validates the explicit execution-layer inventory for every legacy method. */
final class LegacyTestLayerInventory {
	private static final String RESOURCE = "/legacy-test-layer-inventory.csv";
	private final Map<String, Layer> layersByIdentity;

	private LegacyTestLayerInventory(Map<String, Layer> layersByIdentity) {
		this.layersByIdentity = Map.copyOf(layersByIdentity);
	}

	static LegacyTestLayerInventory of(Map<String, Layer> layersByIdentity) {
		return new LegacyTestLayerInventory(layersByIdentity);
	}

	static LegacyTestLayerInventory load() {
		InputStream stream = LegacyTestLayerInventory.class.getResourceAsStream(RESOURCE);
		if (stream == null) {
			throw new IllegalStateException("Missing legacy layer inventory resource: " + RESOURCE);
		}

		Map<String, Layer> entries = new LinkedHashMap<>();
		try (BufferedReader reader = new BufferedReader(
				new InputStreamReader(stream, StandardCharsets.UTF_8))) {
			String line;
			int lineNumber = 0;
			while ((line = reader.readLine()) != null) {
				lineNumber++;
				String trimmed = line.trim();
				if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.equals("identity,layer")) {
					continue;
				}
				String[] columns = trimmed.split(",", -1);
				if (columns.length != 2 || columns[0].isBlank() || columns[1].isBlank()) {
					throw new IllegalStateException(
							"Invalid legacy layer inventory row " + lineNumber + ": " + trimmed);
				}
				String identity = columns[0].trim();
				Layer layer;
				try {
					layer = Layer.valueOf(columns[1].trim());
				} catch (IllegalArgumentException e) {
					throw new IllegalStateException(
							"Unknown legacy test layer at row " + lineNumber + ": " + columns[1].trim(), e);
				}
				if (entries.putIfAbsent(identity, layer) != null) {
					throw new IllegalStateException("Duplicate legacy layer inventory identity: " + identity);
				}
			}
		} catch (IOException e) {
			throw new IllegalStateException("Could not read legacy layer inventory: " + RESOURCE, e);
		}
		return new LegacyTestLayerInventory(entries);
	}

	int size() {
		return layersByIdentity.size();
	}

	void validateCoverage(Collection<TestSequenceDiscovery.DiscoveredSequence> sequences) {
		Set<String> discovered = discoveredIdentities(sequences);
		Set<String> inventoried = layersByIdentity.keySet();

		Set<String> missing = new LinkedHashSet<>(discovered);
		missing.removeAll(inventoried);
		Set<String> unknown = new LinkedHashSet<>(inventoried);
		unknown.removeAll(discovered);
		if (!missing.isEmpty() || !unknown.isEmpty()) {
			throw new IllegalStateException(
					"Legacy layer inventory does not match discovery: missing=" + summarize(missing)
							+ ", unknown=" + summarize(unknown));
		}
	}

	void validateReady(Collection<TestSequenceDiscovery.DiscoveredSequence> sequences) {
		validateCoverage(sequences);
		long unverified = layersByIdentity.values().stream().filter(layer -> layer == Layer.UNVERIFIED).count();
		if (unverified > 0) {
			throw new IllegalStateException(
					"Legacy sequence execution is blocked: " + unverified
							+ " UNVERIFIED methods must each be classified as COMPONENT_DAO, "
							+ "BACKEND_INTEGRATION, or DEPLOYED_E2E");
		}
	}

	Layer layerOf(String identity) {
		Layer layer = layersByIdentity.get(identity);
		if (layer == null) {
			throw new IllegalStateException("Missing inventory entry for legacy test: " + identity);
		}
		return layer;
	}

	Map<Layer, Integer> countByLayer() {
		EnumMap<Layer, Integer> counts = new EnumMap<>(Layer.class);
		for (Layer layer : Layer.values()) {
			counts.put(layer, 0);
		}
		for (Layer layer : layersByIdentity.values()) {
			counts.put(layer, counts.get(layer) + 1);
		}
		return Map.copyOf(counts);
	}

	static Set<Layer> parseLayerFilter(String raw) {
		if (raw == null || raw.isBlank()) {
			return EnumSet.of(Layer.COMPONENT_DAO, Layer.BACKEND_INTEGRATION, Layer.DEPLOYED_E2E);
		}
		EnumSet<Layer> selected = EnumSet.noneOf(Layer.class);
		for (String part : raw.split(",", -1)) {
			String trimmed = part.trim();
			if (trimmed.isEmpty()) {
				throw new IllegalStateException("Empty layer name in starexec.legacy.layers: " + raw);
			}
			Layer layer;
			try {
				layer = Layer.valueOf(trimmed);
			} catch (IllegalArgumentException e) {
				throw new IllegalStateException("Unknown legacy test layer: " + trimmed, e);
			}
			if (layer == Layer.UNVERIFIED) {
				throw new IllegalStateException("UNVERIFIED layer cannot be selected for execution");
			}
			selected.add(layer);
		}
		return Set.copyOf(selected);
	}

	List<TestSequenceDiscovery.DiscoveredSequence> filter(
			List<TestSequenceDiscovery.DiscoveredSequence> sequences,
			Set<Layer> selectedLayers) {
		validateReady(sequences);
		List<TestSequenceDiscovery.DiscoveredSequence> filtered = new ArrayList<>();
		for (TestSequenceDiscovery.DiscoveredSequence sequence : sequences) {
			List<java.lang.reflect.Method> matching = new ArrayList<>();
			for (java.lang.reflect.Method method : sequence.testMethods()) {
				String identity = TestSequenceDiscovery.identity(sequence.sequenceClass(), method);
				if (selectedLayers.contains(layerOf(identity))) {
					matching.add(method);
				}
			}
			if (!matching.isEmpty()) {
				filtered.add(new TestSequenceDiscovery.DiscoveredSequence(sequence.sequenceClass(), matching));
			}
		}
		if (TestSequenceDiscovery.countMethods(filtered) == 0) {
			throw new IllegalStateException(
					"Legacy sequence layer filter matched zero methods for layers " + selectedLayers);
		}
		return List.copyOf(filtered);
	}

	private static Set<String> discoveredIdentities(
			Collection<TestSequenceDiscovery.DiscoveredSequence> sequences) {
		Set<String> identities = new LinkedHashSet<>();
		for (TestSequenceDiscovery.DiscoveredSequence sequence : sequences) {
			for (java.lang.reflect.Method method : sequence.testMethods()) {
				String identity = TestSequenceDiscovery.identity(sequence.sequenceClass(), method);
				if (!identities.add(identity)) {
					throw new IllegalStateException("Duplicate discovered legacy test identity: " + identity);
				}
			}
		}
		return identities;
	}

	private static String summarize(Collection<String> identities) {
		if (identities.isEmpty()) {
			return "0";
		}
		List<String> ordered = new ArrayList<>(identities);
		ordered.sort(String::compareTo);
		int displayed = Math.min(5, ordered.size());
		return ordered.size() + " " + ordered.subList(0, displayed)
				+ (ordered.size() > displayed ? " ..." : "");
	}

	enum Layer {
		COMPONENT_DAO,
		BACKEND_INTEGRATION,
		DEPLOYED_E2E,
		UNVERIFIED
	}
}
