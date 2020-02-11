package org.dspace.app.rest.matcher;

import static com.jayway.jsonpath.matchers.JsonPathMatchers.hasJsonPath;
import static org.dspace.app.rest.matcher.HalMatcher.matchEmbeds;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;

import org.hamcrest.Matcher;

/**
 * Created by: Andrew Wood
 * Date: 10 Feb 2020
 */
public class HarvesterMetadataMatcher {

    HarvesterMetadataMatcher() {
    }

    /**
     * Gets a matcher for all expected embeds when the full projection is requested.
     */
    public static Matcher<? super Object> matchFullEmbeds() {
        return matchEmbeds(
                "harvestermetadata"
        );
    }

    /**
     * Gets a matcher for all expected links.
     */
    public static Matcher<? super Object> matchLinks() {
        return allOf(
                hasJsonPath("$._links.self.href", containsString("api/core/collections/")));
    }
}
