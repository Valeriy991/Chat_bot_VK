package com.example.vkbot.vk;

import com.example.vkbot.config.VkProperties;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.http.client.MultipartBodyBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Component
public class VkApiClient {

    private static final Logger log = LoggerFactory.getLogger(VkApiClient.class);
    private static final String API_BASE_URL = "https://api.vk.com/method";
    private static final String UNREACHABLE_UPLOAD_HOST = "pu.vk.com";
    private static final String REACHABLE_UPLOAD_HOST = "pu.vk.ru";
    private static final int CONNECT_TIMEOUT_MILLIS = 10_000;
    private static final int RESPONSE_TIMEOUT_MILLIS = 40_000;
    private static final int MAX_UPLOAD_ATTEMPTS = 5;
    private static final long UPLOAD_RETRY_DELAY_MILLIS = 200L;

    private final RestClient apiClient;
    private final RestClient rawClient;
    private final VkProperties properties;

    public VkApiClient(RestClient.Builder builder, VkProperties properties) {
        var connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(Timeout.ofMilliseconds(CONNECT_TIMEOUT_MILLIS))
                        .build())
                .build();
        CloseableHttpClient httpClient = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .disableAutomaticRetries()
                .disableCookieManagement()
                .build();
        HttpComponentsClientHttpRequestFactory requestFactory =
                new HttpComponentsClientHttpRequestFactory(httpClient);
        requestFactory.setConnectionRequestTimeout(CONNECT_TIMEOUT_MILLIS);
        requestFactory.setReadTimeout(RESPONSE_TIMEOUT_MILLIS);

        this.apiClient = builder
                .requestFactory(requestFactory)
                .baseUrl(API_BASE_URL)
                .build();
        this.rawClient = RestClient.builder()
                .requestFactory(requestFactory)
                .build();
        this.properties = properties;
    }

    public LongPollServer getLongPollServer() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("group_id", Long.toString(properties.groupId()));

        JsonNode response = call("groups.getLongPollServer", form).path("response");
        String key = requiredText(response, "key");
        String server = requiredText(response, "server");
        String ts = requiredText(response, "ts");

        return new LongPollServer(key, server, ts);
    }

    public JsonNode poll(LongPollServer longPollServer) {
        URI uri = UriComponentsBuilder.fromUriString(longPollServer.server())
                .queryParam("act", "a_check")
                .queryParam("key", longPollServer.key())
                .queryParam("ts", longPollServer.ts())
                .queryParam("wait", 25)
                .build(true)
                .toUri();

        JsonNode response = rawClient.get()
                .uri(uri)
                .retrieve()
                .body(JsonNode.class);

        if (response == null) {
            throw new VkApiException("VK Long Poll returned an empty response");
        }
        return response;
    }

    public String uploadDocumentForMessages(Resource pdf, long recipientPeerId) {
        if (recipientPeerId <= 0) {
            throw new IllegalArgumentException("Recipient peer ID must be positive: " + recipientPeerId);
        }

        List<Long> uploadPeerCandidates = uploadPeerCandidates(recipientPeerId, properties.uploadPeerId());
        log.info(
                "Starting VK PDF upload fileName={} contentLength={} recipientPeerId={} "
                        + "configuredUploadPeerId={} peerCandidates={}",
                Objects.requireNonNullElse(pdf.getFilename(), "<unknown>"),
                safeContentLength(pdf),
                recipientPeerId,
                properties.uploadPeerId(),
                uploadPeerCandidates
        );

        List<VkApiException> rejectedPeers = new ArrayList<>();
        for (long uploadPeerId : uploadPeerCandidates) {
            try {
                return uploadDocumentWithRetries(pdf, UploadTarget.messages(uploadPeerId));
            } catch (VkApiException e) {
                if (!isUploadPeerRejected(e)) {
                    throw e;
                }
                rejectedPeers.add(e);
                log.warn(
                        "VK rejected messages document upload peerId={} apiMethod={} apiCode={}; "
                                + "trying the next upload strategy",
                        uploadPeerId,
                        e.method(),
                        e.errorCode()
                );
            }
        }

        log.warn("All explicit messages upload peers were rejected; trying messages upload without peer_id");
        try {
            return uploadDocumentWithRetries(pdf, UploadTarget.messagesWithoutPeer());
        } catch (VkApiException e) {
            if (!isUploadPeerRejected(e)) {
                throw e;
            }
            rejectedPeers.add(e);
            log.warn(
                    "VK rejected messages document upload without peer_id apiMethod={} apiCode={}; "
                            + "trying wall upload",
                    e.method(),
                    e.errorCode()
            );
        }

        log.warn(
                "All messages upload strategies were rejected; trying wall upload for communityId={}",
                properties.groupId()
        );
        try {
            return uploadDocumentWithRetries(pdf, UploadTarget.wall(properties.groupId()));
        } catch (RuntimeException wallFailure) {
            rejectedPeers.forEach(wallFailure::addSuppressed);
            throw wallFailure;
        }
    }

    private String uploadDocumentWithRetries(Resource pdf, UploadTarget target) {
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_UPLOAD_ATTEMPTS; attempt++) {
            try {
                log.info(
                        "VK PDF upload attempt {}/{} targetType={} targetId={}",
                        attempt,
                        MAX_UPLOAD_ATTEMPTS,
                        target.type(),
                        target.id()
                );
                return uploadDocumentOnce(pdf, target);
            } catch (RuntimeException e) {
                if (!isTransientUploadFailure(e) || attempt == MAX_UPLOAD_ATTEMPTS) {
                    throw e;
                }
                lastFailure = e;
                log.info(
                        "Transient VK upload response on attempt {}/{} for targetType={} targetId={}; "
                                + "retrying with a fresh upload URL",
                        attempt,
                        MAX_UPLOAD_ATTEMPTS,
                        target.type(),
                        target.id()
                );
                sleepBeforeUploadRetry();
            }
        }
        throw new VkApiException("VK document upload failed after retries", lastFailure);
    }

    private String uploadDocumentOnce(Resource pdf, UploadTarget target) {
        JsonNode uploadServerResponse = getDocumentUploadServer(target);
        String uploadUrl = requiredText(uploadServerResponse, "upload_url");
        URI originalUploadUri = URI.create(uploadUrl);
        URI uploadUri = normalizeUploadUri(uploadUrl);

        log.info(
                "VK upload server received targetType={} targetId={} uploadHost={} normalizedUploadHost={}",
                target.type(),
                target.id(),
                originalUploadUri.getHost(),
                uploadUri.getHost()
        );

        MultipartBodyBuilder multipart = new MultipartBodyBuilder();
        multipart.part("file", pdf)
                .filename(Objects.requireNonNullElse(pdf.getFilename(), "Где_мои_силы_—_Анастасия_Гулина_психолог_КПТ.pdf"))
                .contentType(MediaType.APPLICATION_PDF);

        RestClient.RequestBodySpec uploadRequest = rawClient.post()
                .uri(uploadUri)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(multipart.build());
        if (!Objects.equals(originalUploadUri.getHost(), uploadUri.getHost())) {
            uploadRequest.header(HttpHeaders.HOST, originalUploadUri.getHost());
        }

        JsonNode uploadResponse = uploadRequest
                .retrieve()
                .body(JsonNode.class);

        if (uploadResponse == null || uploadResponse.path("file").asText().isBlank()) {
            throw new VkApiException("VK upload server did not return the 'file' field: " + uploadResponse);
        }
        log.info("PDF bytes accepted by VK upload server; saving document metadata");

        MultiValueMap<String, String> saveForm = new LinkedMultiValueMap<>();
        saveForm.add("file", uploadResponse.path("file").asText());
        saveForm.add("title", Objects.requireNonNullElse(pdf.getFilename(), "Где_мои_силы_—_Анастасия_Гулина_психолог_КПТ.pdf"));

        JsonNode savedDocument = call("docs.save", saveForm)
                .path("response")
                .path("doc");

        long ownerId = requiredLong(savedDocument, "owner_id");
        long documentId = requiredLong(savedDocument, "id");
        String accessKey = savedDocument.path("access_key").asText("");

        String attachment = "doc" + ownerId + "_" + documentId;
        if (!accessKey.isBlank()) {
            attachment += "_" + accessKey;
        }
        log.info(
                "VK document saved ownerId={} documentId={} accessKeyPresent={}",
                ownerId,
                documentId,
                !accessKey.isBlank()
        );
        return attachment;
    }

    private JsonNode getDocumentUploadServer(UploadTarget target) {
        if (target.type() == UploadTargetType.MESSAGES
                || target.type() == UploadTargetType.MESSAGES_WITHOUT_PEER) {
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("type", "doc");
            if (target.type() == UploadTargetType.MESSAGES) {
                form.add("peer_id", messagesUploadPeerId(target.id()));
                log.info(
                        "Calling docs.getMessagesUploadServer with peerId={} exceedsLegacyInt32={}",
                        target.id(),
                        target.id() > Integer.MAX_VALUE
                );
            } else {
                log.info("Calling docs.getMessagesUploadServer without peer_id");
            }
            return call("docs.getMessagesUploadServer", form).path("response");
        }

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("group_id", wallUploadGroupId(target.id()));
        log.info("Calling docs.getWallUploadServer with groupId={}", target.id());
        return call("docs.getWallUploadServer", form).path("response");
    }

    private static boolean isUploadPeerRejected(VkApiException failure) {
        return failure.isApiError("docs.getMessagesUploadServer", 15)
                || failure.isApiError("docs.getMessagesUploadServer", 27)
                || failure.isApiError("docs.getMessagesUploadServer", 100)
                || failure.isApiError("docs.getMessagesUploadServer", 113)
                || failure.isApiError("docs.getMessagesUploadServer", 901);
    }

    static List<Long> uploadPeerCandidates(long recipientPeerId, long configuredUploadPeerId) {
        if (recipientPeerId <= 0) {
            throw new IllegalArgumentException("Recipient peer ID must be positive: " + recipientPeerId);
        }

        List<Long> candidates = new ArrayList<>(2);
        candidates.add(recipientPeerId);
        if (configuredUploadPeerId > 0 && configuredUploadPeerId != recipientPeerId) {
            candidates.add(configuredUploadPeerId);
        }
        return List.copyOf(candidates);
    }

    private static long safeContentLength(Resource resource) {
        try {
            return resource.contentLength();
        } catch (Exception e) {
            log.debug("Could not determine PDF content length before upload", e);
            return -1L;
        }
    }

    private static boolean isTransientUploadFailure(RuntimeException failure) {
        if (failure instanceof HttpClientErrorException httpError) {
            return httpError.getStatusCode().value() == 405;
        }
        if (failure instanceof ResourceAccessException) {
            return true;
        }
        return failure instanceof VkApiException
                && failure.getMessage() != null
                && failure.getMessage().contains("no_file");
    }

    private static void sleepBeforeUploadRetry() {
        try {
            Thread.sleep(UPLOAD_RETRY_DELAY_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new VkApiException("VK document upload retry was interrupted", e);
        }
    }

    static URI normalizeUploadUri(String uploadUrl) {
        URI uri = URI.create(uploadUrl);
        if (!UNREACHABLE_UPLOAD_HOST.equalsIgnoreCase(uri.getHost())) {
            return uri;
        }

        log.info("Using VK upload host {} instead of {} because of a TLS connectivity issue",
                REACHABLE_UPLOAD_HOST, UNREACHABLE_UPLOAD_HOST);
        return UriComponentsBuilder.fromUri(uri)
                .host(REACHABLE_UPLOAD_HOST)
                .build(true)
                .toUri();
    }

    public boolean isGroupMember(long userId) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("group_id", Long.toString(properties.groupId()));
        form.add("user_id", Long.toString(userId));

        JsonNode response = call("groups.isMember", form).path("response");
        if (response.isIntegralNumber()) {
            return response.asInt() == 1;
        }
        if (response.isObject() && response.path("member").isIntegralNumber()) {
            return response.path("member").asInt() == 1;
        }
        throw new VkApiException("Unexpected groups.isMember response: " + response);
    }

    public void sendMessage(long peerId, String text, String attachment, int randomId) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("peer_id", Long.toString(peerId));
        form.add("random_id", Integer.toString(randomId));
        form.add("message", text);
        if (attachment != null && !attachment.isBlank()) {
            form.add("attachment", attachment);
        }

        call("messages.send", form);
    }

    public void replyToWallComment(
            long ownerId,
            long postId,
            long commentId,
            String text,
            String guid
    ) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("owner_id", Long.toString(ownerId));
        form.add("post_id", Long.toString(postId));
        form.add("reply_to_comment", Long.toString(commentId));
        form.add("from_group", "1");
        form.add("message", text);
        form.add("guid", guid);

        call("wall.createComment", form);
    }

    private JsonNode call(String method, MultiValueMap<String, String> form) {
        LinkedMultiValueMap<String, String> request = new LinkedMultiValueMap<>();
        request.addAll(form);
        request.add("access_token", properties.token());
        request.add("v", properties.apiVersion());

        JsonNode root;
        try {
            root = apiClient.post()
                    .uri("/" + method)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(request)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (Exception e) {
            throw new VkApiException("VK API transport error for method " + method, e);
        }

        if (root == null) {
            throw new VkApiException("VK API returned an empty response for method " + method);
        }
        if (root.has("error")) {
            JsonNode error = root.path("error");
            throw new VkApiException(
                    method,
                    error.path("error_code").asInt(),
                    error.path("error_msg").asText()
            );
        }
        return root;
    }

    private static String requiredText(JsonNode node, String field) {
        String value = node.path(field).asText("");
        if (value.isBlank()) {
            throw new VkApiException("Required VK response field is missing: " + field + "; payload=" + node);
        }
        return value;
    }

    private static long requiredLong(JsonNode node, String field) {
        if (!node.has(field) || !node.path(field).canConvertToLong()) {
            throw new VkApiException("Required VK response field is missing: " + field + "; payload=" + node);
        }
        return node.path(field).asLong();
    }

    static String wallUploadGroupId(long groupId) {
        if (groupId <= 0) {
            throw new VkApiException("VK group ID must be positive for document upload: " + groupId);
        }
        return Long.toString(groupId);
    }

    static String messagesUploadPeerId(long peerId) {
        if (peerId <= 0) {
            throw new IllegalStateException("VK upload peer ID must be positive: " + peerId);
        }
        return Long.toString(peerId);
    }

    private enum UploadTargetType {
        MESSAGES,
        MESSAGES_WITHOUT_PEER,
        WALL
    }

    private record UploadTarget(UploadTargetType type, long id) {

        private static UploadTarget messages(long peerId) {
            return new UploadTarget(UploadTargetType.MESSAGES, peerId);
        }

        private static UploadTarget messagesWithoutPeer() {
            return new UploadTarget(UploadTargetType.MESSAGES_WITHOUT_PEER, 0L);
        }

        private static UploadTarget wall(long groupId) {
            return new UploadTarget(UploadTargetType.WALL, groupId);
        }
    }
}
