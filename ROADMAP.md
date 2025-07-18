# Roadmap 2025

This is a roadmap with major features planned for Marginalia Search.

It's not set in any particular order and other features will definitely 
be implemented as well.

Major goals:

* Reach 1 billion pages indexed


* Improve technical ability of indexing and search.  ~~Although this area has improved a bit, the
  search engine is still not very good at dealing with longer queries.~~  (As of PR [#129](https://github.com/MarginaliaSearch/MarginaliaSearch/pull/129), this has improved significantly.  There is still more work to be done )

## Hybridize crawler w/ Common Crawl data

Sometimes Marginalia's relatively obscure crawler is blocked when attempting to crawl a website, or for
other technical reasons it may be prevented from doing so.  A possible work-around is to hybridize the 
crawler so that it attempts to fetch such inaccessible websites from common crawl.  This is an important 
step on the road to 1 billion pages indexed.

As a rough sketch, the crawler would identify target websites, consume CC's index, and then fetch the WARC data
with byte range queries.  

Retaining the ability to independently crawl the web is still strongly desirable so going full CC is not an option.

## Safe Search

The search engine has a bit of a problem showing spicy content mixed in with the results.  It would be desirable to have a way to filter this out.  It's likely something like a URL blacklist (e.g. [UT1](https://dsi.ut-capitole.fr/blacklists/index_en.php) )
combined with naive bayesian filter would go a long way, or something more sophisticated...?

## Additional Language Support

It would be desirable if the search engine supported more languages than English.  This is partially about
rooting out assumptions regarding character encoding, but there's most likely some amount of custom logic
associated with each language added, at least a models file or two, as well as some fine tuning.

It would be very helpful to find a speaker of a large language other than English to help in the fine tuning.

## Custom ranking logic

Stract does an interesting thing where they have configurable search filters.

This looks like a good idea that wouldn't just help clean up the search filters on the main
website, but might be cheap enough we might go as far as to offer a number of ad-hoc custom search
filter for any API consumer.

I've talked to the stract dev and he does not think it's a good idea to mimic their optics language, which is quite ad-hoc, but instead to work together to find some new common description language for this. 

## Specialized crawler for github

One of the search engine's biggest limitations right now is that it does not index github at all.   A specialized crawler that fetches at least the readme.md would go a long way toward providing search capabilities in this domain.

# Completed

## Support for binary formats like PDF (COMPLETED 2025-05)

The crawler needs to be modified to retain them, and the conversion logic needs to parse them.  
The documents database probably should have some sort of flag indicating it's a PDF as well.

PDF parsing is known to be a bit of a security liability so some thought needs to be put in
that direction as well.

## Show favicons next to search results (COMPLETED 2025-03)

This is expected from search engines.  Basic proof of concept sketch of fetching this data has been done, but the feature is some way from being reality.

## Web Design Overhaul (COMPLETED 2025-01)

The design is kinda clunky and hard to maintain, and needlessly outdated-looking.  

PR [#127](https://github.com/MarginaliaSearch/MarginaliaSearch/pull/127)

## Finalize RSS support (COMPLETED 2024-11)

Marginalia has experimental RSS preview support for a few domains.  This works well and
it should be extended to all domains.  It would also be interesting to offer search of the
RSS data itself, or use the RSS set to feed a special live index that updates faster than the
main dataset. 

Completed with PR [#122](https://github.com/MarginaliaSearch/MarginaliaSearch/pull/122) and PR [#125](https://github.com/MarginaliaSearch/MarginaliaSearch/pull/125)

## Proper Position Index (COMPLETED 2024-09)

The search engine uses a fixed width bit mask to indicate word positions.  It has the benefit
of being very fast to evaluate and works well for what it is, but is inaccurate and has the 
drawback of making support for quoted search terms inaccurate and largely reliant on indexing 
word n-grams known beforehand.  This limits the ability to interpret longer queries.

The positions mask should be supplemented or replaced with a more accurate (e.g.) gamma coded positions
list, as is the civilized way of doing this.

Completed with PR [#99](https://github.com/MarginaliaSearch/MarginaliaSearch/pull/99)

