package com.systemdesign.leetcode.service;

import com.systemdesign.leetcode.model.Submission;
import com.systemdesign.leetcode.model.TestCase;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Volume;
import com.github.dockerjava.core.DockerClientBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class CodeExecutionService {
    private static final long EXECUTION_TIMEOUT_MS = 5000;
    private static final long MEMORY_LIMIT_BYTES = 256 * 1024 * 1024; // 256MB
    private static final long CPU_QUOTA = 50000; // 50% of one CPU core

    private final DockerClient dockerClient = DockerClientBuilder.getInstance().build();

    public Submission.SubmissionResult executeCode(
            String code,
            String language,
            List<TestCase> testCases,
            Submission submission) {

        try {
            Path tempDir = Files.createTempDirectory("leetcode-exec");
            File codeFile = createCodeFile(tempDir, code, language);

            String imageName = getDockerImageForLanguage(language);

            int passedTests = 0;
            long totalExecutionTime = 0;

            for (TestCase testCase : testCases) {
                ExecutionResult result = executeTestCase(
                    imageName,
                    codeFile,
                    testCase,
                    language,
                    tempDir
                );

                totalExecutionTime += result.executionTimeMs;

                if (result.executionTimeMs > EXECUTION_TIMEOUT_MS) {
                    submission.setErrorMessage("Time Limit Exceeded");
                    return Submission.SubmissionResult.TIME_LIMIT_EXCEEDED;
                }

                if (result.exitCode != 0) {
                    submission.setErrorMessage(result.stderr);
                    return Submission.SubmissionResult.RUNTIME_ERROR;
                }

                if (result.stdout.trim().equals(testCase.getExpectedOutput().trim())) {
                    passedTests++;
                } else {
                    submission.setOutput("Expected: " + testCase.getExpectedOutput() +
                                       "\nGot: " + result.stdout);
                    submission.setTestCasesPassed(passedTests);
                    submission.setTotalTestCases(testCases.size());
                    return Submission.SubmissionResult.WRONG_ANSWER;
                }
            }

            submission.setTestCasesPassed(passedTests);
            submission.setTotalTestCases(testCases.size());
            submission.setExecutionTimeMs(totalExecutionTime);

            Files.deleteIfExists(codeFile.toPath());
            Files.deleteIfExists(tempDir);

            return Submission.SubmissionResult.ACCEPTED;

        } catch (Exception e) {
            log.error("Error executing code", e);
            submission.setErrorMessage(e.getMessage());
            return Submission.SubmissionResult.RUNTIME_ERROR;
        }
    }

    private ExecutionResult executeTestCase(
            String imageName,
            File codeFile,
            TestCase testCase,
            String language,
            Path tempDir) throws Exception {

        HostConfig hostConfig = new HostConfig()
            .withMemory(MEMORY_LIMIT_BYTES)
            .withCpuQuota(CPU_QUOTA)
            .withNetworkMode("none")
            .withReadonlyRootfs(true)
            .withBinds(new Bind(tempDir.toString(), new Volume("/workspace")));

        CreateContainerResponse container = dockerClient.createContainerCmd(imageName)
            .withHostConfig(hostConfig)
            .withWorkingDir("/workspace")
            .withCmd("sleep", "10")
            .exec();

        String containerId = container.getId();

        try {
            dockerClient.startContainerCmd(containerId).exec();

            String executeCommand = buildExecuteCommand(language, codeFile.getName());

            ExecCreateCmdResponse execCreateCmd = dockerClient.execCreateCmd(containerId)
                .withCmd("/bin/sh", "-c", executeCommand)
                .withAttachStdout(true)
                .withAttachStderr(true)
                .exec();

            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();

            long startTime = System.currentTimeMillis();

            dockerClient.execStartCmd(execCreateCmd.getId())
                .exec(new ExecCallback(stdout, stderr))
                .awaitCompletion(EXECUTION_TIMEOUT_MS, TimeUnit.MILLISECONDS);

            long executionTime = System.currentTimeMillis() - startTime;

            Integer exitCode = dockerClient.inspectExecCmd(execCreateCmd.getId())
                .exec()
                .getExitCodeLong()
                .intValue();

            return new ExecutionResult(
                stdout.toString(),
                stderr.toString(),
                exitCode,
                executionTime
            );

        } finally {
            dockerClient.stopContainerCmd(containerId).exec();
            dockerClient.removeContainerCmd(containerId).exec();
        }
    }

    private File createCodeFile(Path tempDir, String code, String language) throws Exception {
        String extension = getFileExtension(language);
        File codeFile = tempDir.resolve("Solution" + extension).toFile();

        try (FileWriter writer = new FileWriter(codeFile)) {
            writer.write(code);
        }

        return codeFile;
    }

    private String getDockerImageForLanguage(String language) {
        return switch (language.toLowerCase()) {
            case "java" -> "openjdk:17-slim";
            case "python" -> "python:3.11-slim";
            case "javascript" -> "node:20-slim";
            case "cpp" -> "gcc:latest";
            case "go" -> "golang:1.21-alpine";
            default -> throw new IllegalArgumentException("Unsupported language: " + language);
        };
    }

    private String getFileExtension(String language) {
        return switch (language.toLowerCase()) {
            case "java" -> ".java";
            case "python" -> ".py";
            case "javascript" -> ".js";
            case "cpp" -> ".cpp";
            case "go" -> ".go";
            default -> ".txt";
        };
    }

    private String buildExecuteCommand(String language, String fileName) {
        return switch (language.toLowerCase()) {
            case "java" -> "javac " + fileName + " && java Solution";
            case "python" -> "python " + fileName;
            case "javascript" -> "node " + fileName;
            case "cpp" -> "g++ " + fileName + " -o solution && ./solution";
            case "go" -> "go run " + fileName;
            default -> throw new IllegalArgumentException("Unsupported language: " + language);
        };
    }

    private static class ExecutionResult {
        String stdout;
        String stderr;
        int exitCode;
        long executionTimeMs;

        ExecutionResult(String stdout, String stderr, int exitCode, long executionTimeMs) {
            this.stdout = stdout;
            this.stderr = stderr;
            this.exitCode = exitCode;
            this.executionTimeMs = executionTimeMs;
        }
    }

    private static class ExecCallback extends com.github.dockerjava.api.async.ResultCallback.Adapter<com.github.dockerjava.api.model.Frame> {
        private final ByteArrayOutputStream stdout;
        private final ByteArrayOutputStream stderr;

        ExecCallback(ByteArrayOutputStream stdout, ByteArrayOutputStream stderr) {
            this.stdout = stdout;
            this.stderr = stderr;
        }

        @Override
        public void onNext(com.github.dockerjava.api.model.Frame frame) {
            try {
                switch (frame.getStreamType()) {
                    case STDOUT, RAW -> stdout.write(frame.getPayload());
                    case STDERR -> stderr.write(frame.getPayload());
                }
            } catch (Exception e) {
                log.error("Error processing frame", e);
            }
        }
    }
}
