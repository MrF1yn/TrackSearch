package io.sfrei.tracksearch.clients.youtube;

import io.sfrei.tracksearch.clients.helper.ClientHelper;
import io.sfrei.tracksearch.clients.setup.*;
import io.sfrei.tracksearch.config.TrackSearchConfig;
import io.sfrei.tracksearch.config.TrackSearchConstants;
import io.sfrei.tracksearch.exceptions.TrackSearchException;
import io.sfrei.tracksearch.exceptions.YouTubeException;
import io.sfrei.tracksearch.tracks.BaseTrackList;
import io.sfrei.tracksearch.tracks.Track;
import io.sfrei.tracksearch.tracks.TrackList;
import io.sfrei.tracksearch.tracks.YouTubeTrack;
import io.sfrei.tracksearch.tracks.metadata.YouTubeTrackFormat;
import io.sfrei.tracksearch.tracks.metadata.YouTubeTrackInfo;
import io.sfrei.tracksearch.utils.*;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import retrofit2.Call;
import retrofit2.Retrofit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
public class YouTubeClient extends SingleSearchClient<YouTubeTrack> implements ClientHelper {

    public static final String HOSTNAME = "https://www.youtube.com";
    public static final String PAGING_KEY = "ctoken";
    private static final String INFORMATION_PREFIX = "yt";
    public static final String POSITION_KEY = INFORMATION_PREFIX + TrackSearchConfig.POSITION_KEY_SUFFIX;
    public static final String OFFSET_KEY = INFORMATION_PREFIX + TrackSearchConfig.OFFSET_KEY_SUFFIX;
    private static final String PAGING_INFORMATION = INFORMATION_PREFIX + "PagingToken";
    private static final String ADDITIONAL_PAGING_KEY = "continuation";

    private static final Map<String, String> VIDEO_SEARCH_PARAMS = Map.of("sp", "EgIQAQ%3D%3D");
    private static final Map<String, String> TRACK_PARAMS = Map.of("pbj", "1", "hl", "en", "alt", "json");

    private static final Map<String, String> DEFAULT_SEARCH_PARAMS;

    static {
        DEFAULT_SEARCH_PARAMS = new HashMap<>();
        DEFAULT_SEARCH_PARAMS.putAll(VIDEO_SEARCH_PARAMS);
        DEFAULT_SEARCH_PARAMS.putAll(TRACK_PARAMS);
    }

    private final YouTubeService requestService;
    private final YouTubeUtility youTubeUtility;

    private final CacheMap<String, String> scriptCache;

    public YouTubeClient() {

        super(
                (uri, cookie) -> cookie.getName().equals("YSC") || cookie.getName().equals("VISITOR_INFO1_LIVE") || cookie.getName().equals("GPS"),
                Map.of("Cookie", "CONSENT=YES+cb.20210328-17-p0.en+FX+478")
        );

        final Retrofit base = new Retrofit.Builder()
                .baseUrl(HOSTNAME)
                .client(okHttpClient)
                .addConverterFactory(ResponseProviderFactory.create())
                .build();

        requestService = base.create(YouTubeService.class);
        youTubeUtility = new YouTubeUtility();
        scriptCache = new CacheMap<>();
    }

    public static Map<String, String> makeQueryInformation(final String query, final String pagingToken) {
        return new HashMap<>(Map.of(TrackList.QUERY_PARAM, query, PAGING_INFORMATION, pagingToken));
    }

    private BaseTrackList<YouTubeTrack> getTracksForSearch(@NonNull final String search, @NonNull final Map<String, String> params, QueryType queryType)
            throws TrackSearchException {

        final Call<ResponseWrapper> request = requestService.getSearchForKeywords(search, params);
        final ResponseWrapper response = Client.request(request);
        final String content = response.getContentOrThrow();
        return youTubeUtility.getYouTubeTracks(content, queryType, search, this::provideStreamUrl);
    }

    @Override
    public BaseTrackList<YouTubeTrack> getTracksForSearch(@NonNull final String search) throws TrackSearchException {
        final BaseTrackList<YouTubeTrack> trackList = getTracksForSearch(search, DEFAULT_SEARCH_PARAMS, QueryType.SEARCH);
        trackList.addQueryInformationValue(POSITION_KEY, 0);
        return trackList;
    }

    @Override
    public BaseTrackList<YouTubeTrack> getRelatedTracks(@NonNull final String videoID) throws TrackSearchException {
        final Call<ResponseWrapper> request = requestService.getVideoPage(videoID, DEFAULT_SEARCH_PARAMS);
        final ResponseWrapper response = Client.request(request);
        final String content = response.getContentOrThrow();
        return youTubeUtility.getRelatedTracks(content, this::provideStreamUrl);
    }

    private String provideStreamUrl(final YouTubeTrack track) {
        try {
            return getStreamUrl(track, TrackSearchConstants.DEFAULT_RESOLVING_RETRIES);
        } catch (TrackSearchException e) {
            log.error("Error occurred acquiring stream URL", e);
        }
        return null;
    }

    @Override
    public BaseTrackList<YouTubeTrack> getNext(@NonNull final TrackList<? extends Track> trackList) throws TrackSearchException {
        throwIfPagingValueMissing(this, trackList);

        final QueryType trackListQueryType = trackList.getQueryType();
        if (trackListQueryType.equals(QueryType.SEARCH) || trackListQueryType.equals(QueryType.PAGING)) {
            final HashMap<String, String> params = new HashMap<>();
            params.putAll(getPagingParams(trackList.getQueryInformation()));
            params.putAll(DEFAULT_SEARCH_PARAMS);

            final BaseTrackList<YouTubeTrack> nextTracksForSearch = getTracksForSearch(trackList.getQueryParam(), params, QueryType.PAGING);
            return TrackListHelper.updatePagingValues(nextTracksForSearch, trackList, POSITION_KEY, OFFSET_KEY);
        }
        throw new YouTubeException(ExceptionUtility.unsupportedQueryTypeMessage(trackListQueryType));
    }

    public YouTubeTrackInfo loadTrackInfo(final YouTubeTrack youtubeTrack) throws TrackSearchException {
        final String trackUrl = youtubeTrack.getUrl();
        final Call<ResponseWrapper> trackRequest = requestService.getForUrlWithParams(trackUrl, TRACK_PARAMS);
        final ResponseWrapper trackResponse = Client.request(trackRequest);

        final String trackContent = trackResponse.getContentOrThrow();
        final YouTubeTrackInfo trackInfo = youTubeUtility.getTrackInfo(trackContent, trackUrl, this::requestURL);
        return youtubeTrack.setAndGetTrackInfo(trackInfo);
    }

    @Override
    public String getStreamUrl(@NonNull final YouTubeTrack youtubeTrack) throws TrackSearchException {

        final YouTubeTrackInfo trackInfo = loadTrackInfo(youtubeTrack);

        final YouTubeTrackFormat youtubeTrackFormat = TrackFormatUtility.getBestTrackFormat(youtubeTrack, false);

        if (youtubeTrackFormat.isStreamReady())
            return URLUtility.decode(youtubeTrackFormat.getUrl());

        final String scriptUrl = trackInfo.getScriptUrl();
        if (scriptUrl == null)
            throw new TrackSearchException("ScriptURL could not be resolved");

        final String scriptContent;
        if (scriptCache.containsKey(scriptUrl)) {
            log.trace("Use cached script for: {}", scriptUrl);
            scriptContent = scriptCache.get(scriptUrl);
        } else {
            scriptContent = requestURL(HOSTNAME + scriptUrl).getContentOrThrow();
            scriptCache.put(scriptUrl, scriptContent);
        }

        final String signature = youTubeUtility.getSignature(youtubeTrackFormat, scriptUrl, scriptContent);
        final String unauthorizedStreamUrl = youtubeTrackFormat.getUrl();

        return URLUtility.addRequestParam(unauthorizedStreamUrl, youtubeTrackFormat.getSigParam(), signature);
    }

    @Override
    public String getStreamUrl(@NonNull final YouTubeTrack youtubeTrack, final int retries) throws TrackSearchException {
        return getStreamUrl(this, youtubeTrack, this::requestAndGetCode, retries)
                .orElseThrow(() -> new YouTubeException(ExceptionUtility.noStreamUrlAfterRetriesMessage(retries)));
    }

    private Map<String, String> getPagingParams(final Map<String, String> queryInformation) {
        final String pagingToken = queryInformation.get(PAGING_INFORMATION);
        return Map.of(PAGING_KEY, pagingToken, ADDITIONAL_PAGING_KEY, pagingToken);
    }

    @Override
    public boolean hasPagingValues(@NonNull final TrackList<? extends Track> trackList) {
        return TrackListHelper.hasQueryInformation(trackList, POSITION_KEY, OFFSET_KEY, PAGING_INFORMATION);
    }

    @Override
    public Logger logger() {
        return log;
    }

}
