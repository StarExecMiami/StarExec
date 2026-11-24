package org.starexec.test.integration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/** Writes SHA-bound JSON and CSV execution reports for legacy {@link TestSequence} runs. */
final class LegacySequenceReport {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	static final class MethodRecord {
		final String identity;
		final String method;
		final String layer;
		final String status;
		final long durationMs;
		final String error;

		MethodRecord(String identity, String method, String layer, String status, long durationMs, String error) {
			this.identity = identity;
			this.method = method;
			this.layer = layer;
			this.status = status;
			this.durationMs = durationMs;
			this.error = error;
		}
	}

	static final class SequenceRecord {
		final String sequence;
		final String status;
		final long durationMs;
		final List<String> lifecycleFailures = new ArrayList<>();
		final List<MethodRecord> methods = new ArrayList<>();

		SequenceRecord(String sequence, String status, long durationMs) {
			this.sequence = sequence;
			this.status = status;
			this.durationMs = durationMs;
		}
	}

	private LegacySequenceReport() {
	}

	static String resolveCommitSha() {
		String prop = System.getProperty("starexec.legacy.gitSha");
		if (prop != null && !prop.isBlank()) {
			return prop.trim();
		}
		String env = System.getenv("GITHUB_SHA");
		if (env != null && !env.isBlank()) {
			return env.trim();
		}
		return "unknown";
	}

	static void writeReports(
			Path reportDir,
			String commitSha,
			Set<LegacyTestLayerInventory.Layer> selectedLayers,
			Map<LegacyTestLayerInventory.Layer, Integer> inventoryCountsByLayer,
			List<SequenceRecord> sequences) throws IOException {
		Files.createDirectories(reportDir);

		int totalMethods = 0;
		int passedMethods = 0;
		int failedMethods = 0;
		int failedSequences = 0;
		for (SequenceRecord seq : sequences) {
			if (!"PASSED".equals(seq.status)) {
				failedSequences++;
			}
			for (MethodRecord m : seq.methods) {
				totalMethods++;
				if ("PASSED".equals(m.status)) {
					passedMethods++;
				} else {
					failedMethods++;
				}
			}
		}

		Map<String, Object> root = new LinkedHashMap<>();
		root.put("commitSha", commitSha);
		root.put("selectedLayers", selectedLayers.stream().map(Enum::name).sorted().toList());
		Map<String, Integer> counts = new LinkedHashMap<>();
		inventoryCountsByLayer.entrySet().stream()
				.sorted(Map.Entry.comparingByKey())
				.forEach(e -> counts.put(e.getKey().name(), e.getValue()));
		root.put("inventoryCountsByLayer", counts);
		root.put("totalSequences", sequences.size());
		root.put("failedSequences", failedSequences);
		root.put("totalMethods", totalMethods);
		root.put("passedMethods", passedMethods);
		root.put("failedMethods", failedMethods);
		root.put("sequences", sequences);

		Files.writeString(
				reportDir.resolve("legacy-sequence-report.json"),
				GSON.toJson(root) + "\n",
				StandardCharsets.UTF_8);

		StringBuilder csv = new StringBuilder();
		csv.append("commit_sha,sequence,method,layer,status,duration_ms,error\n");
		for (SequenceRecord seq : sequences) {
			for (MethodRecord m : seq.methods) {
				csv.append(csvField(commitSha)).append(',')
						.append(csvField(seq.sequence)).append(',')
						.append(csvField(m.method)).append(',')
						.append(csvField(m.layer)).append(',')
						.append(csvField(m.status)).append(',')
						.append(m.durationMs).append(',')
						.append(csvField(m.error == null ? "" : m.error))
						.append('\n');
			}
		}
		Files.writeString(
				reportDir.resolve("legacy-sequence-report.csv"),
				csv.toString(),
				StandardCharsets.UTF_8);
	}

	private static String csvField(String value) {
		String sanitized = value.replace("\r", " ").replace("\n", " ");
		if (sanitized.contains(",") || sanitized.contains("\"")) {
			return "\"" + sanitized.replace("\"", "\"\"") + "\"";
		}
		return sanitized;
	}
}
