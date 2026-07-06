package com.example.server.utils;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
public class YtDlpUtils {

    @Value("${tool.ytdlp.path}")
    private String ytDlpPath;

    @Value("${tool.ffmpeg.dir}")
    private String ffmpegDir;

    public File downloadVideo(String url) throws Exception {
        String tempDir = System.getProperty("java.io.tmpdir");
        String outputName = UUID.randomUUID().toString() + ".mp4";
        String outputPath = tempDir + File.separator + outputName;

        System.out.println("⬇️ [yt-dlp] 开始下载: " + url);

        // 从 URL 提取域名，决定策略
        String host = extractHost(url);
        System.out.println("🌐 [yt-dlp] 检测到平台: " + host);

        List<String> command = new ArrayList<>();
        command.add(ytDlpPath);

        // --- 浏览器 Cookie（各平台通用）---
        String cookiesFromBrowser = findBrowserWithCookies(host);
        if (cookiesFromBrowser != null) {
            command.add("--cookies-from-browser");
            command.add(cookiesFromBrowser);
            System.out.println("🔐 [yt-dlp] 使用浏览器 Cookie: " + cookiesFromBrowser);
        }

        // 现代浏览器 User-Agent
        command.add("--user-agent");
        command.add("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36");

        // 请求头伪装（对所有平台有效）
        command.add("--add-header");
        command.add("Accept:text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8");
        command.add("--add-header");
        command.add("Accept-Language:zh-CN,zh;q=0.9,en;q=0.8");

        // 根据平台设置合适的 Referer
        command.add("--referer");
        command.add("https://" + host + "/");

        // Bilibili 专用参数：跳过 DASH/HLS 格式，直取 mp4
        if (host.contains("bilibili")) {
            command.add("--extractor-args");
            command.add("bilibili:player=html5;skip=hls,dash");
        }

        command.add("--ffmpeg-location");
        command.add(ffmpegDir);

        command.add("-o");
        command.add(outputPath);

        // 其他选项
        command.add("--no-check-certificate");
        command.add("--no-playlist");

        command.add(url);

        // 打印完整命令便于调试
        System.out.println("🔧 [yt-dlp] 命令: " + String.join(" ", command));

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);

        Process process = pb.start();

        StringBuilder logs = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains("ERROR") || line.contains("Downloading") || line.contains("[Merger]")
                        || line.contains("Extracting") || line.contains("412")) {
                    System.out.println("cmd > " + line);
                }
                logs.append(line).append("\n");
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            String errorMsg = logs.toString();

            // 412 专用提示
            if (errorMsg.contains("412") || errorMsg.contains("Precondition Failed")) {
                String hint = "";
                if (cookiesFromBrowser == null) {
                    hint = "\n\n💡 提示: " + host + " 需要登录凭证。请在 Chrome/Edge 浏览器中登录后重试。";
                } else {
                    hint = "\n\n💡 提示: Cookie 可能已过期，请在浏览器中重新登录 " + host + " 后重试。";
                }
                throw new RuntimeException("平台反爬拦截 (HTTP 412)，需要浏览器登录凭证。" + hint);
            }

            // 403 反爬拦截（B站 WBI 签名失败等）
            if (errorMsg.contains("403") || errorMsg.contains("Forbidden")) {
                String hint = "";
                if (cookiesFromBrowser == null) {
                    hint = "\n\n💡 提示: " + host + " 反爬拦截 (403)。请先关闭 Chrome/Edge 浏览器（否则 Cookie 数据库被锁定），然后重启服务重试。如果仍然失败，请在浏览器中登录 " + host + " 后再次关闭浏览器重试。";
                } else {
                    hint = "\n\n💡 提示: " + host + " 反爬拦截 (403)。Cookie 可能已过期，请在 " + cookiesFromBrowser + " 浏览器中重新登录 " + host + "，然后关闭浏览器后重试。";
                }
                throw new RuntimeException("平台反爬拦截 (HTTP 403) —— " + host + " 拒绝了请求。" + hint);
            }

            // Cookie 需求提示（各平台通用）
            if (errorMsg.contains("cookies are needed") || errorMsg.contains("Fresh cookies")) {
                String hint = "";
                if (cookiesFromBrowser == null) {
                    hint = "\n\n💡 提示: " + host + " 需要浏览器 Cookie。请先关闭 Chrome/Edge 浏览器（解锁 Cookie 数据库），重启服务后重试。如果仍不行，请在浏览器中登录 " + host + " 后关闭浏览器再试。";
                } else {
                    hint = "\n\n💡 提示: Cookie 可能已过期或未登录。请在 " + cookiesFromBrowser + " 浏览器中访问并登录 " + host + "，然后关闭浏览器后重试。";
                }
                throw new RuntimeException("平台要求浏览器 Cookie 验证。" + hint);
            }

            throw new RuntimeException("yt-dlp 下载失败: " + errorMsg);
        }

        File downloadedFile = new File(outputPath);
        if (!downloadedFile.exists()) {
            throw new RuntimeException("下载显示成功但文件未生成");
        }

        System.out.println("✅ [yt-dlp] 下载完成: " + (downloadedFile.length() / 1024) + "KB");
        return downloadedFile;
    }

    /**
     * 查找系统中可用的浏览器（按目标平台域名测试 Cookie 提取）
     */
    private String findBrowserWithCookies(String targetDomain) {
        // 按优先级尝试各浏览器
        String[] browsers = {"chrome", "edge", "firefox", "chromium", "opera", "brave"};
        for (String browser : browsers) {
            try {
                // 用 yt-dlp 自带的 cookie 提取测试
                List<String> testCmd = new ArrayList<>();
                testCmd.add(ytDlpPath);
                testCmd.add("--cookies-from-browser");
                testCmd.add(browser);
                testCmd.add("--print");
                testCmd.add("cookies_from_browser_works");
                testCmd.add(targetDomain);

                ProcessBuilder pb = new ProcessBuilder(testCmd);
                pb.redirectErrorStream(true);

                Process p = pb.start();
                StringBuilder out = new StringBuilder();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                    String l;
                    while ((l = r.readLine()) != null) { out.append(l); }
                }
                p.waitFor();

                String result = out.toString();
                // 能成功提取 cookie 就返回
                if (!result.contains("ERROR") && !result.contains("not find")
                        && !result.contains("PermissionError") && !result.contains("Chrome requires")) {
                    System.out.println("✅ [yt-dlp] 浏览器 Cookie 可用: " + browser + " (" + targetDomain + ")");
                    return browser;
                } else {
                    System.out.println("⚠️ [yt-dlp] 浏览器 " + browser + " Cookie 提取失败: " + result.trim());
                }
            } catch (Exception e) {
                System.out.println("⚠️ [yt-dlp] 浏览器 " + browser + " 不可用: " + e.getMessage());
            }
        }
        System.out.println("❌ [yt-dlp] 未找到可用浏览器 Cookie（请关闭 Chrome 等浏览器后重试，否则 Cookie 数据库被锁定）");
        return null;
    }

    /**
     * 从 URL 中提取域名
     */
    private String extractHost(String url) {
        try {
            java.net.URI uri = new java.net.URI(url.startsWith("http") ? url : "https://" + url);
            String host = uri.getHost();
            if (host != null) {
                // 去掉 www. 前缀
                return host.startsWith("www.") ? host.substring(4) : host;
            }
        } catch (Exception ignored) {}
        return "unknown";
    }
}