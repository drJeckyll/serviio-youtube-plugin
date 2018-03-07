import groovy.json.JsonSlurper

import org.serviio.library.metadata.*
import org.serviio.library.online.*

/**
 * YouTube.com content URL extractor plugin.
 *
 * It uses YouTube API v3 for retrieving playlists.
 *
 * @see http://en.wikipedia.org/wiki/Youtube#Quality_and_codecs
 *
 * @author Petr Nejedly
 * @modified drJeckyll
 * @modified Pavlo Kudlay
 * tested on serviio 1.9.1
 */
class YouTube extends WebResourceUrlExtractor {

    final VALID_RESOURCE_URL = '^https?://www.googleapis.com/youtube/.*$'
    /* Listed in order of quality */
    final availableFormats = ['37', '46', '22', '45', '35', '34', '18', '44', '43', '6', '5']

    String getExtractorName() {
        return getClass().getName()
    }

    boolean extractorMatches(URL feedUrl) {
        return feedUrl ==~ VALID_RESOURCE_URL
    }

    public int getVersion() {
        return 4;
    }

    @Override
    WebResourceContainer extractItems(URL resourceUrl, int maxItemsToRetrieve) {
        final user_agent = "Mozilla/5.0 (compatible; MSIE 10.0; Windows NT 6.1; Trident/6.0)"
        final api_key = "AIzaSyAPeRAlHMSa_WQMbarc_ffse7-t0-DOuVQ"
        // this is Serviio API key, don't use it for anything else please, they are easy to get

        if (maxItemsToRetrieve == -1) maxItemsToRetrieve = 50

        // Handle channel name urls
        if (resourceUrl.toString().contains("channels")) {
            def channelUrl = new URL(resourceUrl.toString() + "&part=contentDetails" + "&key=" + api_key)
            def channeljson = new JsonSlurper().parseText(openURL(channelUrl, user_agent))
            def uploadPlaylist = channeljson.items[0].contentDetails.relatedPlaylists.uploads

            resourceUrl = new URL("https://www.googleapis.com/youtube/v3/playlistItems?playlistId=$uploadPlaylist")
        }

        def apiUrl = new URL(resourceUrl.toString() + "&part=snippet" + "&maxResults=" + maxItemsToRetrieve + "&type=video" + "&key=" + api_key)
        def json = new JsonSlurper().parseText(openURL(apiUrl, user_agent))

        def items = []

        // for long playlists it may take a while to update
        def i = 0
        while (json.pageInfo.totalResults > i) {
            json.items.each() {
                i++
                if (it.snippet.title != "Deleted video" && it.snippet.title != "Private video") {
                    items.add(new WebResourceItem(title: it.snippet.title,
                            additionalInfo: ['videoId': resourceUrl.toString().contains("videos") ? it.id : resourceUrl.toString().contains("search") ? it.id.videoId : it.snippet.resourceId.videoId,
                                             'thumb'  : it.snippet.thumbnails.high.url]))
                }
            }
            if (json.nextPageToken != null) {
                // repeat with supplied token
                apiUrl = new URL(resourceUrl.toString() + "&part=snippet" + "&maxResults=" + maxItemsToRetrieve + "&type=video" + "&key=" + api_key + "&pageToken=" + json.nextPageToken)
                json = new JsonSlurper().parseText(openURL(apiUrl, user_agent))
            }
        }

        def containerThumbnailUrl = items?.find { it -> it.additionalInfo['thumb'] != null }?.additionalInfo['thumb']
        return new WebResourceContainer(items: items, thumbnailUrl: containerThumbnailUrl)
    }

    @Override
    protected ContentURLContainer extractUrl(WebResourceItem wrItem, PreferredQuality requestedQuality) {
        def contentUrl
        def expiryDate
        def expiresImmediately
        def cacheKey

        def videoId = wrItem.additionalInfo['videoId']
        def thumbnailUrl = wrItem.additionalInfo['thumb']

        for (elType in ['&el=embedded', '&el=detailpage', '&el=vevo', '']) {
            def videoInfoUrl = "http://www.youtube.com/get_video_info?&video_id=$videoId$elType&ps=default&eurl=&gl=US&hl=en"
            //log("Loading video info: $videoInfoUrl")
            def videoInfoWebPage = new URL(videoInfoUrl).getText()
            def parameters = [:]

            videoInfoWebPage.split('&').each { item -> addParameter(item, parameters, '=') }
            if (parameters.containsKey('token')) {
                def formatUrlMapString = parameters['fmt_url_map']
                def urlEncodedUrlMapString = parameters['url_encoded_fmt_stream_map']
                def adaptiveFormatsMapString = parameters['adaptive_fmts']
                def hlsFormatsmapString = parameters['hlsvp']
                def allFormatUrlMap = [:]

                //def hls = URLDecoder.decode(hlsFormatsmapString,'UTF-8')//.split(',').each{item -> addParameter(item, allFormatUrlMap, '\\|')}

                def test = URLDecoder.decode(adaptiveFormatsMapString, 'UTF-8').split(',').each { item -> addParameter(item, allFormatUrlMap, '\\|') }

                if (formatUrlMapString != null && formatUrlMapString.length() > 0) {
                    URLDecoder.decode(formatUrlMapString, 'UTF-8').split(',').each { item -> addParameter(item, allFormatUrlMap, '\\|') }
                } else {
                    if (urlEncodedUrlMapString != null && urlEncodedUrlMapString.length() > 0) {
                        processStreamData(urlEncodedUrlMapString, allFormatUrlMap)
                    }
                    if (adaptiveFormatsMapString != null && adaptiveFormatsMapString.length() > 0) {
                        processStreamData(adaptiveFormatsMapString, allFormatUrlMap)
                    }
                }
                // get available formats for requested quality, sorted by quality from highest
                def formatUrlMap = new LinkedHashMap()
                if (requestedQuality == PreferredQuality.HIGH) {
                    // best quality, get the first from the list
                    sortAvailableFormatUrls(availableFormats, allFormatUrlMap, formatUrlMap)
                    def selectedUrl = formatUrlMap.entrySet().toList().head()
                    contentUrl = selectedUrl.getValue()
                    cacheKey = getCacheKey(videoId, selectedUrl.getKey())
                } else if (requestedQuality == PreferredQuality.MEDIUM) {
                    // work with subset of available formats, starting at the position of format 35 and then take the best quality from there
                    sortAvailableFormatUrls(availableFormats.getAt(4..availableFormats.size - 1), allFormatUrlMap, formatUrlMap)
                    def selectedUrl = formatUrlMap.entrySet().toList().head()
                    contentUrl = selectedUrl.getValue()
                    cacheKey = getCacheKey(videoId, selectedUrl.getKey())
                } else {
                    // worst quality, take the last url
                    sortAvailableFormatUrls(availableFormats, allFormatUrlMap, formatUrlMap)
                    def selectedUrl = formatUrlMap.entrySet().toList().last()
                    contentUrl = selectedUrl.getValue()
                    cacheKey = getCacheKey(linkUrl, selectedUrl.getKey())
                }
                if (contentUrl != null) {
                    expiresImmediately = true
                    if (contentUrl.startsWith('http')) {
                        // http URL
                        def contentUrlParameters = [:]
                        contentUrl.split('&').each { item -> addParameter(item, contentUrlParameters, '=') }
                        if (contentUrlParameters['expire'] != null) {
                            //log(Long.parseLong(contentUrlParameters['expire']).toString())
                            expiryDate = new Date(Long.parseLong(contentUrlParameters['expire']) * 1000)
                            expiresImmediately = false
                        }
                    } else {
                        // rtmp URL
                        def rtmpMatcher = contentUrl =~ 'rtmpe?://.*?/(.*)'
                        def app = rtmpMatcher[0][1]
                        // TODO load swf player URL from the HTML page
                        contentUrl = "$contentUrl app=$app swfUrl=http://s.ytimg.com/yt/swfbin/watch_as3-vflg0Q-LP.swf swfVfy=1"
                    }
                }

                break
            }
        }
        return new ContentURLContainer(fileType: MediaFileType.VIDEO, contentUrl: contentUrl, thumbnailUrl: thumbnailUrl, expiresOn: expiryDate, expiresImmediately: expiresImmediately, cacheKey: cacheKey)
    }

    def addParameter(parameterString, parameters, separator) {
        def values = parameterString.split(separator)
        if (values.length == 2) {
            parameters.put(values[0], values[1])
        }
    }

    def processStreamData(String urlEncodedUrlMapString, Map streamsMap) {
        URLDecoder.decode(urlEncodedUrlMapString, 'UTF-8').split(',').each { item ->
            def streamParams = [:]
            item.split('&').each { item2 -> addParameter(item2, streamParams, '=') }
            String urlKeyName = streamParams.containsKey('url') ? 'url' : 'conn'
            //String stream = URLDecoder.decode(streamParams['stream'],'UTF-8') //TODO stream is playpath
            String streamUrl = URLDecoder.decode(streamParams[urlKeyName], 'UTF-8')
            String signature = streamParams['sig']
            if (signature) streamUrl = streamUrl + "&signature=" + signature
            streamsMap.put(streamParams['itag'], streamUrl)
        }
    }

    def String getCacheKey(String videoId, String qualityId) {
        "youtube_${videoId}_${qualityId}"
    }

    def sortAvailableFormatUrls(List formatIds, Map sourceMap, Map targetMap) {
        formatIds.each { formatId ->
            if (sourceMap.containsKey(formatId)) {
                targetMap.put(formatId, sourceMap.get(formatId))
            }
        }
    }

    static void main(args) {
        // this is just to test
        YouTube extractor = new YouTube()

        assert extractor.extractorMatches(new URL("https://www.googleapis.com/youtube/v3/playlistItems?playlistId=YOUR_PLAYLIST_ID_HERE"))
        assert !extractor.extractorMatches(new URL("http://google.com/feeds/api/standardfeeds/top_rated?time=today"))

        WebResourceContainer container = extractor.extractItems(new URL("https://www.googleapis.com/youtube/v3/playlistItems?playlistId=PL5D6FB3CCE0053D01"), 10)
        println container
        ContentURLContainer result = extractor.extractUrl(container.getItems()[2], PreferredQuality.MEDIUM)
        println result

        println ""
        container = extractor.extractItems(new URL("https://www.googleapis.com/youtube/v3/videos?chart=mostPopular"), 10)
        println container
        result = extractor.extractUrl(container.getItems()[2], PreferredQuality.MEDIUM)
        println result

        println ""
        container = extractor.extractItems(new URL("https://www.googleapis.com/youtube/v3/channels?forUsername=NFL"), 10)
        println container
        result = extractor.extractUrl(container.getItems()[2], PreferredQuality.MEDIUM)
        println result

        println ""
        container = extractor.extractItems(new URL("https://www.googleapis.com/youtube/v3/search?q=crazy"), 10)
        println container
        result = extractor.extractUrl(container.getItems()[2], PreferredQuality.MEDIUM)
        println result

//	  live hls
//	  println ""
//	 WebResourceContainer container = extractor.extractItems( new URL("https://www.googleapis.com/youtube/v3/videos?id=sw4hmqVPe0E"), 10)
//	  println container
//	  ContentURLContainer result = extractor.extractUrl(container.getItems()[0], PreferredQuality.MEDIUM)
//	  println result


    }

}