package com.example.server.utils;

import com.alibaba.dashscope.audio.asr.recognition.Recognition;
import com.alibaba.dashscope.audio.asr.recognition.RecognitionParam;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 阿里云通义听悟 (Paraformer) 语音识别工具类
 * <p>
 * 使用 DashScope SDK 的 Recognition API，将音频文件实时识别为文字。
 * 支持中/英/日/粤语等多种语言，识别准确率业界领先。
 */
@Component
public class AliyunAsrUtils {

    @Value("${ai.dashscope.api-key:}")
    private String apiKey;

    @Value("${tool.ffmpeg.dir}")
    private String ffmpegDir;

    /**
     * 语音转文字（通义听悟 Paraformer）
     *
     * @param filePath 音频文件路径（支持 MP3/WAV 等 FFmpeg 可解码格式）
     * @return 识别出的完整文本
     */
    public String audioToText(String filePath) {
        File file = new File(filePath);
        if (!file.exists()) return "❌ 错误：找不到文件";

        String pcmPath = null;
        try {
            System.out.println("🎤 [通义听悟] 开始识别: " + file.getName());

            // Step 1: 转成 PCM (16kHz, 单声道, 16-bit signed little-endian)
            pcmPath = convertToPcm(filePath);

            // Step 2: 调用通义听悟实时识别 API
            RecognitionParam param = RecognitionParam.builder()
                    .apiKey(apiKey)
                    .format("pcm")
                    .model("paraformer-realtime-v2")
                    .sampleRate(16000)
                    .build();

            Recognition recognizer = new Recognition();
            String rawJson = recognizer.call(param, new File(pcmPath));

            if (rawJson != null && !rawJson.isBlank()) {
                // 解析 JSON，提取每句的 text 字段拼接成纯文本
                String text = extractTextFromResult(rawJson);
                if (text != null && !text.isBlank()) {
                    System.out.println("✅ [通义听悟] 识别完成: " + text.length() + " 字");
                    return text;
                }
            }
            return "（未识别到语音内容）";

        } catch (Exception e) {
            System.err.println("❌ [通义听悟] 失败: " + e.getMessage());
            e.printStackTrace();
            return "❌ 语音识别失败: " + e.getMessage();
        } finally {
            // 清理临时 PCM 文件
            if (pcmPath != null) {
                try {
                    Files.deleteIfExists(Path.of(pcmPath));
                } catch (Exception ignored) {}
            }
        }
    }

    /**
     * 从通义听悟 API 返回的 JSON 中提取纯文本
     * JSON 格式: {"sentences":[{"text":"...","begin_time":0,...}, ...]}
     */
    private String extractTextFromResult(String rawJson) {
        try {
            JSONObject root = JSON.parseObject(rawJson);
            if (root == null) return rawJson;

            // 尝试从 sentences 数组提取
            JSONArray sentences = root.getJSONArray("sentences");
            if (sentences != null && !sentences.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < sentences.size(); i++) {
                    JSONObject sentence = sentences.getJSONObject(i);
                    String text = sentence.getString("text");
                    if (text != null && !text.isBlank()) {
                        if (sb.length() > 0) sb.append("\n");
                        sb.append(text);
                    }
                }
                return sb.toString();
            }

            // 降级：尝试单句 text 字段
            String text = root.getString("text");
            if (text != null && !text.isBlank()) return text;

            // 实在解析不了，返回原始内容
            return rawJson;
        } catch (Exception e) {
            System.err.println("⚠️ [通义听悟] JSON 解析失败，返回原始内容: " + e.getMessage());
            return rawJson;
        }
    }

    /**
     * 用 FFmpeg 将音频转成 16kHz 单声道 PCM
     */
    private String convertToPcm(String inputPath) throws Exception {
        String pcmPath = inputPath + ".pcm";

        List<String> command = new ArrayList<>();
        command.add(ffmpegDir + File.separator + "ffmpeg");
        command.add("-y");
        command.add("-i");
        command.add(inputPath);
        command.add("-ar");
        command.add("16000");
        command.add("-ac");
        command.add("1");
        command.add("-f");
        command.add("s16le");
        command.add(pcmPath);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        StringBuilder logs = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                logs.append(line).append("\n");
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("FFmpeg PCM 转换失败: " + logs);
        }

        return pcmPath;
    }
}
