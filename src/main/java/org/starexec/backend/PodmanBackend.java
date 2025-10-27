package org.starexec.backend;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Volume;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import org.starexec.logger.StarLogger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

/**
 * Backend implementation for Docker/Podman container execution.
 * Executes job scripts as isolated containers with resource limits.
 */
public class PodmanBackend implements Backend {
	private static final StarLogger log = StarLogger.getLogger(PodmanBackend.class);
	private DockerClient dockerClient;
	private final String baseImage = "ubuntu:22.04";
	private Map<Integer, String> execIdToContainerId = new HashMap<>();
	private int nextExecId = 1000;

	@Override
	public void initialize(String backendRoot) {
		try {
			log.info("Initializing PodmanBackend...");
			DockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder()
					.withDockerHost("unix:///var/run/docker.sock")
					.build();

			ApacheDockerHttpClient httpClient = new ApacheDockerHttpClient.Builder()
					.dockerHost(config.getDockerHost())
					.build();

			this.dockerClient = DockerClientImpl.getInstance(config, httpClient);
			log.info("PodmanBackend initialized successfully.");
		} catch (Exception e) {
			log.error("Fatal error initializing PodmanBackend", e);
			throw new IllegalStateException("Could not connect to the container engine.", e);
		}
	}

	@Override
	public void destroyIf() {
		if (dockerClient != null) {
			try {
				dockerClient.close();
				dockerClient = null;
				log.info("PodmanBackend destroyed.");
			} catch (Exception e) {
				log.warn("Error closing Docker client", e);
			}
		}
	}

	@Override
	public boolean isError(int execCode) {
		return execCode < 0;
	}

	@Override
	public int submitScript(String scriptPath, String workingDirectory, String logPath) {
		try {
			String jobId = "starexec-job-" + System.currentTimeMillis();
			String imageName = "starexec/job:" + System.currentTimeMillis();

			// Create Dockerfile
			String dockerfileContent =
					"FROM " + baseImage + "\n" +
					"RUN apt-get update && apt-get install -y --no-install-recommends bash\n" +
					"WORKDIR /starexec\n" +
					"COPY . /starexec/\n" +
					"RUN chmod +x " + scriptPath + "\n" +
					"ENTRYPOINT [\"bash\", \"" + scriptPath + "\"]";

			Files.writeString(Paths.get(workingDirectory, "Dockerfile"), dockerfileContent);

			// Build image
			String imageId = dockerClient.buildImageCmd()
					.withDockerfile(new java.io.File(workingDirectory, "Dockerfile"))
					.withBaseDirectory(new java.io.File(workingDirectory))
					.withTags(Collections.singleton(imageName))
					.start()
					.awaitImageId();

			log.info("Image built: " + imageId);

			// Create container
			Volume outputVolume = new Volume("/starexec/output");
			Bind outputBind = new Bind(logPath, outputVolume);

			HostConfig hostConfig = new HostConfig()
					.withBinds(outputBind)
					.withMemory(1073741824L); // 1GB default

			CreateContainerResponse container = dockerClient.createContainerCmd(imageName)
					.withName(jobId)
					.withHostConfig(hostConfig)
					.exec();

			String containerId = container.getId();
			dockerClient.startContainerCmd(containerId).exec();

			// Generate execution ID and store mapping
			int execId = nextExecId++;
			execIdToContainerId.put(execId, containerId);

			log.info("Container started: " + containerId + " with exec ID: " + execId);
			return execId;

		} catch (Exception e) {
			log.error("Error submitting script", e);
			return -1;
		}
	}

	@Override
	public boolean killPair(int execId) {
		String containerId = execIdToContainerId.get(execId);
		if (containerId == null) {
			log.warn("Container not found for execId: " + execId);
			return false;
		}

		try {
			dockerClient.stopContainerCmd(containerId).exec();
			dockerClient.removeContainerCmd(containerId).exec();
			execIdToContainerId.remove(execId);
			return true;
		} catch (Exception e) {
			log.error("Error killing container", e);
			return false;
		}
	}

	@Override
	public boolean killAll() {
		try {
			for (Integer execId : new ArrayList<>(execIdToContainerId.keySet())) {
				killPair(execId);
			}
			return true;
		} catch (Exception e) {
			log.error("Error killing all containers", e);
			return false;
		}
	}

	@Override
	public String getRunningJobsStatus() {
		try {
			StringBuilder status = new StringBuilder();
			List<Container> containers = dockerClient.listContainersCmd().exec();
			for (Container container : containers) {
				if (Arrays.stream(container.getNames()).anyMatch(n -> n.contains("starexec"))) {
					status.append(String.format("Container: %s Status: %s\n", 
						container.getId(), container.getStatus()));
				}
			}
			return status.toString();
		} catch (Exception e) {
			log.error("Error getting running jobs status", e);
			return "Error retrieving status";
		}
	}

	@Override
	public Set<Integer> getActiveExecutionIds() throws IOException {
		return new HashSet<>(execIdToContainerId.keySet());
	}

	@Override
	public String[] getWorkerNodes() {
		log.warn("Worker nodes not applicable for container backend");
		return new String[0];
	}

	@Override
	public String[] getQueues() {
		log.warn("Queues not applicable for container backend");
		return new String[0];
	}

	@Override
	public Map<String, String> getNodeQueueAssociations() {
		return new HashMap<>();
	}

	@Override
	public boolean clearNodeErrorStates() {
		return true;
	}

	@Override
	public void deleteQueue(String queueName) {
		log.warn("deleteQueue not applicable for container backend");
	}

	@Override
	public boolean createQueue(String newQueueName, String[] nodeNames, String[] sourceQueueNames) {
		log.warn("createQueue not applicable for container backend");
		return true;
	}

	@Override
	public boolean createQueueWithSlots(String newQueueName, String[] nodeNames, String[] sourceQueueNames, Integer slots) {
		log.warn("createQueueWithSlots not applicable for container backend");
		return true;
	}

	@Override
	public void moveNodes(String destQueueName, String[] nodeNames, String[] sourceQueueNames) {
		log.warn("moveNodes not applicable for container backend");
	}

	@Override
	public void moveNode(String nodeName, String queueName) {
		log.warn("moveNode not applicable for container backend");
	}
}