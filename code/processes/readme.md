# Processes

## 1. Crawl Process

The [crawling-process](crawling-process/) fetches website contents, temporarily saving them as WARC files, and then
re-converts them into parquet models.  Both are described in [crawling-process/model](crawling-process/model/).

## 2. Converting Process

The [converting-process](converting-process/) reads crawl data from the crawling step and 
processes them, extracting keywords and metadata and saves them as parquet files 
described in [converting-process/model](converting-process/model/).

## 3. Loading Process

The [loading-process](loading-process/) reads the processed data.

It has creates an [index journal](../index/index-journal), 
a [link database](../common/linkdb), 
and loads domains and domain-links 
into the [MariaDB database](../common/db).

## 4. Index Construction Process

The [index-construction-process](index-constructor-process/) constructs indices from
the data generated by the loader.

## 5. Other Processes

* Ping Process: The [ping-process](ping-process/) keeps track of the aliveness of websites, gathering fingerprint information about the security posture of the website, as well as DNS information.
* New Domain Process (NDP): The [new-domain-process](new-domain-process/) evaluates new domains for inclusion in the search engine index.
* Live-Crawling Process: The [live-crawling-process](live-crawling-process/) is a process that crawls websites in real-time based on RSS feeds, updating a smaller index with the latest content.

## Overview 

Schematically the crawling and loading process looks like this:

```
    +-----------+  
    |  CRAWLING |  Fetch each URL and 
    |    STEP   |  output to file
    +-----------+
          |
    //========================\\
    ||  Parquet:              || Crawl
    ||  Status, HTML[], ...   || Files
    ||  Status, HTML[], ...   ||
    ||  Status, HTML[], ...   ||
    ||     ...                ||
    \\========================//
          |
    +------------+
    | CONVERTING |  Analyze HTML and 
    |    STEP    |  extract keywords 
    +------------+  features, links, URLs
          |
    //==================\\
    || Slop   :         ||  Processed
    ||  Documents[]     ||  Files
    ||  Domains[]       ||
    ||  Links[]         ||  
    \\==================//
          |
    +------------+ Insert domains into mariadb
    |  LOADING   | Insert URLs, titles in link DB
    |    STEP    | Insert keywords in Index
    +------------+    
          |
    +------------+
    | CONSTRUCT  | Make the data searchable
    |   INDEX    | 
    +------------+
```