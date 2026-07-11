package com.cineseekerr.bot.client;

import com.cineseekerr.bot.config.CineSeekerrProperties;
import com.cineseekerr.bot.model.QbtTorrent;
import com.cineseekerr.bot.model.QbtTorrentFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.function.Function;

/**
 * Client for the qBittorrent WebUI API v2.
 *
 * <p>qBittorrent uses cookie-based sessions: {@code /auth/login} returns a {@code SID}
 * cookie that every other endpoint requires and that expires server-side. The client logs
 * in lazily, caches the cookie, and transparently re-authenticates once when a request
 * comes back {@code 403 Forbidden}.
 */
@Component
public class QbittorrentClient {

    private static final Logger log = LoggerFactory.getLogger(QbittorrentClient.class);

    private final RestClient restClient;
    private final String username;
    private final String password;
    private final String baseUrl;

    private volatile String sessionCookie;

    public QbittorrentClient(RestClient.Builder restClientBuilder, CineSeekerrProperties properties) {
        CineSeekerrProperties.Qbittorrent qbittorrent = properties.qbittorrent();
        this.username = qbittorrent.username();
        this.password = qbittorrent.password();
        this.baseUrl = qbittorrent.baseUrl();
        this.restClient = restClientBuilder.baseUrl(qbittorrent.baseUrl()).build();
    }

    /**
     * Adds a torrent by URL (magnet link or Prowlarr-proxied {@code .torrent} URL).
     *
     * @param torrentUrl url accepted by qBittorrent's {@code urls} parameter
     * @param savePath   absolute save path as seen by the qBittorrent container
     * @param category   qBittorrent category, used later to poll only our own downloads
     */
    public void addTorrent(String torrentUrl, String savePath, String category) {
        authenticated(cookie -> {
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("urls", torrentUrl);
            form.add("savepath", savePath);
            form.add("category", category);

            // The success body varies across qBittorrent versions ("Ok." as plain text, or a
            // JSON status object here) so a non-2xx status - already turned into a
            // RestClientException by retrieve() - is the only reliable failure signal.
            String body = restClient.post()
                    .uri("/api/v2/torrents/add")
                    .header(HttpHeaders.COOKIE, cookie)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(String.class);

            log.info("Torrent sent to qBittorrent (category={}, savePath={}, response={})",
                    category, savePath, body);
            return null;
        });
    }

    /** Lists all torrents belonging to {@code category}, used to poll download progress. */
    public List<QbtTorrent> listTorrents(String category) {
        List<QbtTorrent> torrents = authenticated(cookie -> restClient.get()
                .uri(uriBuilder -> uriBuilder.path("/api/v2/torrents/info")
                        .queryParam("category", category)
                        .build())
                .header(HttpHeaders.COOKIE, cookie)
                .retrieve()
                .body(new ParameterizedTypeReference<List<QbtTorrent>>() {
                }));
        return torrents == null ? List.of() : torrents;
    }

    /** Lists the files of a torrent; paths are relative to its save path. */
    public List<QbtTorrentFile> listFiles(String hash) {
        List<QbtTorrentFile> files = authenticated(cookie -> restClient.get()
                .uri(uriBuilder -> uriBuilder.path("/api/v2/torrents/files")
                        .queryParam("hash", hash)
                        .build())
                .header(HttpHeaders.COOKIE, cookie)
                .retrieve()
                .body(new ParameterizedTypeReference<List<QbtTorrentFile>>() {
                }));
        return files == null ? List.of() : files;
    }

    /** Removes a torrent, optionally deleting its downloaded data from disk too. */
    public void deleteTorrent(String hash, boolean deleteFiles) {
        authenticated(cookie -> {
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("hashes", hash);
            form.add("deleteFiles", String.valueOf(deleteFiles));
            restClient.post()
                    .uri("/api/v2/torrents/delete")
                    .header(HttpHeaders.COOKIE, cookie)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .toBodilessEntity();
            return null;
        });
    }

    /** Renames the torrent's root folder; qBittorrent keeps seeding from the new path. */
    public void renameFolder(String hash, String oldPath, String newPath) {
        rename("/api/v2/torrents/renameFolder", hash, oldPath, newPath);
    }

    /** Renames a single file inside the torrent; qBittorrent keeps seeding it. */
    public void renameFile(String hash, String oldPath, String newPath) {
        rename("/api/v2/torrents/renameFile", hash, oldPath, newPath);
    }

    private void rename(String endpoint, String hash, String oldPath, String newPath) {
        authenticated(cookie -> {
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("hash", hash);
            form.add("oldPath", oldPath);
            form.add("newPath", newPath);
            restClient.post()
                    .uri(endpoint)
                    .header(HttpHeaders.COOKIE, cookie)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .toBodilessEntity();
            return null;
        });
    }

    /**
     * Runs {@code call} with a valid session cookie, logging in first if needed and
     * retrying exactly once after a fresh login when the session has expired (403).
     */
    private <T> T authenticated(Function<String, T> call) {
        String cookie = sessionCookie;
        if (cookie == null) {
            cookie = login();
        }
        try {
            return call.apply(cookie);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode() != HttpStatus.FORBIDDEN) {
                throw new ApiClientException("qBittorrent request failed with status "
                        + e.getStatusCode(), e);
            }
            log.debug("qBittorrent session expired, re-authenticating");
            try {
                return call.apply(login());
            } catch (RestClientException retryFailure) {
                throw new ApiClientException("qBittorrent request failed after re-login", retryFailure);
            }
        } catch (RestClientException e) {
            throw new ApiClientException("qBittorrent is unreachable", e);
        }
    }

    private String login() {
        ResponseEntity<String> response;
        try {
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("username", username);
            form.add("password", password);

            response = restClient.post()
                    .uri("/api/v2/auth/login")
                    // qBittorrent 4.6.1+ rejects the login without a Referer matching its
                    // own host, as a CSRF guard against browser-based attacks.
                    .header(HttpHeaders.REFERER, baseUrl)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .toEntity(String.class);
        } catch (RestClientException e) {
            throw new ApiClientException("qBittorrent login failed", e);
        }

        // The success body varies across qBittorrent versions ("Ok." with a 200, or an
        // empty body with a 204) so the session cookie is the only reliable success signal.
        String setCookie = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        if (setCookie == null) {
            throw new ApiClientException(
                    "qBittorrent refused the credentials — check QBITTORRENT_USER/QBITTORRENT_PASS");
        }
        String cookie = setCookie.split(";", 2)[0];
        this.sessionCookie = cookie;
        log.debug("qBittorrent login OK");
        return cookie;
    }
}
