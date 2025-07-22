package nu.marginalia.converting.processor.plugin;

import com.google.inject.Inject;
import com.google.inject.name.Named;
import nu.marginalia.converting.model.DisqualifiedException;
import nu.marginalia.converting.model.DocumentHeaders;
import nu.marginalia.converting.model.GeneratorType;
import nu.marginalia.converting.model.ProcessedDocumentDetails;
import nu.marginalia.converting.processor.DocumentClass;
import nu.marginalia.converting.processor.MetaRobotsTag;
import nu.marginalia.converting.processor.classifier.AcceptableAds;
import nu.marginalia.converting.processor.logic.*;
import nu.marginalia.converting.processor.logic.dom.MeasureLengthVisitor;
import nu.marginalia.converting.processor.logic.links.FileLinks;
import nu.marginalia.converting.processor.logic.links.LinkProcessor;
import nu.marginalia.converting.processor.plugin.specialization.HtmlProcessorSpecializations;
import nu.marginalia.converting.processor.pubdate.PubDateSniffer;
import nu.marginalia.domclassifier.DomSampleClassification;
import nu.marginalia.gregex.GuardedRegex;
import nu.marginalia.gregex.GuardedRegexFactory;
import nu.marginalia.keyword.DocumentKeywordExtractor;
import nu.marginalia.keyword.LinkTexts;
import nu.marginalia.keyword.model.DocumentKeywordsBuilder;
import nu.marginalia.language.filter.LanguageFilter;
import nu.marginalia.language.model.DocumentLanguageData;
import nu.marginalia.language.sentence.ThreadLocalSentenceExtractorProvider;
import nu.marginalia.link_parser.LinkParser;
import nu.marginalia.model.DocumentFormat;
import nu.marginalia.model.EdgeDomain;
import nu.marginalia.model.EdgeUrl;
import nu.marginalia.model.crawl.HtmlFeature;
import nu.marginalia.model.crawl.PubDate;
import nu.marginalia.model.crawldata.CrawledDocument;
import nu.marginalia.model.idx.DocumentFlags;
import nu.marginalia.model.idx.DocumentMetadata;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

import static nu.marginalia.converting.model.DisqualifiedException.DisqualificationReason;


public class HtmlDocumentProcessorPlugin extends AbstractDocumentProcessorPlugin {

    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final double minDocumentQuality;

    private final FeatureExtractor featureExtractor;
    private final DocumentKeywordExtractor keywordExtractor;
    private final PubDateSniffer pubDateSniffer;

    private final DocumentLengthLogic documentLengthLogic;

    private final MetaRobotsTag metaRobotsTag;
    private final DocumentGeneratorExtractor documentGeneratorExtractor;
    private static final DocumentValuator documentValuator = new DocumentValuator();

    private static final LinkParser linkParser = new LinkParser();

    private final ThreadLocalSentenceExtractorProvider sentenceExtractorProvider;
    private final HtmlProcessorSpecializations htmlProcessorSpecializations;

    private static boolean lenientProcessing = Boolean.getBoolean("converter.lenientProcessing");

    @Inject
    public HtmlDocumentProcessorPlugin(
            @Named("min-document-quality") Double minDocumentQuality,
            LanguageFilter languageFilter,
            FeatureExtractor featureExtractor,
            DocumentKeywordExtractor keywordExtractor,
            PubDateSniffer pubDateSniffer,
            DocumentLengthLogic documentLengthLogic,
            MetaRobotsTag metaRobotsTag,
            DocumentGeneratorExtractor documentGeneratorExtractor,
            ThreadLocalSentenceExtractorProvider sentenceExtractorProvider,
            HtmlProcessorSpecializations specializations)
    {
        super(languageFilter);

        this.documentLengthLogic = documentLengthLogic;
        this.minDocumentQuality = minDocumentQuality;
        this.featureExtractor = featureExtractor;

        this.keywordExtractor = keywordExtractor;
        this.pubDateSniffer = pubDateSniffer;
        this.metaRobotsTag = metaRobotsTag;

        this.documentGeneratorExtractor = documentGeneratorExtractor;
        this.sentenceExtractorProvider = sentenceExtractorProvider;
        this.htmlProcessorSpecializations = specializations;
    }

    @Override
    public boolean isApplicable(CrawledDocument doc) {
        return doc.contentType.toLowerCase().contains("html");
    }

    @Override
    public DetailsWithWords createDetails(CrawledDocument crawledDocument,
                                          LinkTexts linkTexts,
                                          Set<DomSampleClassification> domSampleClassifications, DocumentClass documentClass)
            throws DisqualifiedException, URISyntaxException, IOException {

        if (!lenientProcessing && languageFilter.isBlockedUnicodeRange(crawledDocument.documentBody(512))) {
            throw new DisqualifiedException(DisqualificationReason.LANGUAGE);
        }

        Document doc = crawledDocument.parseBody();

        if (!lenientProcessing && AcceptableAds.hasAcceptableAdsTag(doc)) {
            throw new DisqualifiedException(DisqualifiedException.DisqualificationReason.ACCEPTABLE_ADS);
        }

        if (!metaRobotsTag.allowIndexingByMetaTag(doc)) {
            throw new DisqualifiedException(DisqualificationReason.FORBIDDEN);
        }

        final EdgeUrl url = new EdgeUrl(crawledDocument.url);
        final DocumentHeaders documentHeaders = new DocumentHeaders(crawledDocument.headers);

        final var generatorParts = documentGeneratorExtractor.detectGenerator(url, doc, documentHeaders);

        final var specialization = htmlProcessorSpecializations.select(generatorParts, url);

        if (!lenientProcessing && !specialization.shouldIndex(url)) {
            throw new DisqualifiedException(DisqualificationReason.IRRELEVANT);
        }

        var prunedDoc = specialization.prune(doc);

        final int length = getLength(doc);
        final DocumentFormat format = getDocumentFormat(doc);
        final double quality;

        if (domSampleClassifications.contains(DomSampleClassification.UNCLASSIFIED)) {
            quality = documentValuator.getQuality(crawledDocument, format, doc, length);
        }
        else {
            quality = documentValuator.getQuality(domSampleClassifications);
        }

        if (!lenientProcessing && isDisqualified(documentClass, url, quality, doc.title())) {
            throw new DisqualifiedException(DisqualificationReason.QUALITY);
        }

        DocumentLanguageData dld = sentenceExtractorProvider.get().extractSentences(prunedDoc);

        checkDocumentLanguage(dld);

        var ret = new ProcessedDocumentDetails();

        ret.length = length;
        ret.format = format;
        ret.title = specialization.getTitle(doc, dld, crawledDocument.url);

        final Set<HtmlFeature> features = featureExtractor.getFeatures(url, doc, documentHeaders, dld);

        if (!documentLengthLogic.validateLength(dld, specialization.lengthModifier() * documentClass.lengthLimitModifier())) {
            features.add(HtmlFeature.SHORT_DOCUMENT);
        }


        ret.features = features;
        ret.quality = documentValuator.adjustQuality(quality, features);
        ret.hashCode = dld.localitySensitiveHashCode();

        PubDate pubDate = pubDateSniffer.getPubDate(documentHeaders, url, doc, format, true);

        EnumSet<DocumentFlags> documentFlags = documentFlags(features, generatorParts.type());

        ret.metadata = new DocumentMetadata(
                documentLengthLogic.getEncodedAverageLength(dld),
                pubDate.yearByte(),
                (int) -ret.quality, // ret.quality is negative
                documentFlags);

        DocumentKeywordsBuilder words = keywordExtractor.extractKeywords(dld, linkTexts, url);

        ret.description = specialization.getSummary(prunedDoc, words.importantWords);
        ret.generator = generatorParts.type();

        var tagWords = new MetaTagsBuilder()
                .addPubDate(pubDate)
                .addUrl(url)
                .addFeatures(features)
                .addFormat(format)
                .addGenerator(generatorParts.keywords())
                .build();


        words.addAllSyntheticTerms(tagWords);
        specialization.amendWords(doc, words);

        getLinks(url, ret, doc, words);

        if (pubDate.hasYear()) {
            ret.pubYear = pubDate.year();
        }

        return new DetailsWithWords(ret, words);
    }

    private EnumSet<DocumentFlags> documentFlags(Set<HtmlFeature> features, GeneratorType type) {
        EnumSet<DocumentFlags> flags = EnumSet.noneOf(DocumentFlags.class);

        if (features.contains(HtmlFeature.JS)) {
            flags.add(DocumentFlags.Javascript);
        }

        switch (type) {
            case DOCS -> flags.add(DocumentFlags.GeneratorDocs);
            case FORUM -> flags.add(DocumentFlags.GeneratorForum);
            case WIKI -> flags.add(DocumentFlags.GeneratorWiki);
            default -> {} // no flags
        }

        return flags;
    }

    private static final GuardedRegex mastodonFeedRegex = GuardedRegexFactory.startsWith("/@", "^/@[^/]+/?$");

    private boolean isDisqualified(DocumentClass documentClass,
                                   EdgeUrl url,
                                   double quality,
                                   String title) {

        if (documentClass.enforceQualityLimits()
            && quality < minDocumentQuality)
        {
            return true;
        }

        // These pages shouldn't be publicly accessible
        if ("phpinfo()".equals(title)) {
            return true;
        }

        // Urls that look like /@foo are typically Mastodon or other twitter-like feeds,
        // we don't want to index them because they change so rapidly; subdirectories are
        // fine though
        //
        if (mastodonFeedRegex.test(url.path)) {
            return true;
        }

        // Annoying blog crap
        if (url.path.contains("/tag/") && url.path.endsWith("/")) {
            return true;
        }
        if (url.path.contains("/tags/") && url.path.endsWith("/")) {
            return true;
        }
        if (url.path.contains("/category/") && url.path.endsWith("/")) {
            return true;
        }
        if (url.path.contains("/categories/") && url.path.endsWith("/")) {
            return true;
        }
        if (url.path.contains("/section/") && url.path.endsWith("/")) {
            return true;
        }
        if (url.path.contains("/sections/") && url.path.endsWith("/")) {
            return true;
        }
        return false;
    }


    private void getLinks(EdgeUrl baseUrl, ProcessedDocumentDetails ret, Document doc, DocumentKeywordsBuilder words) {

        final LinkProcessor lp = new LinkProcessor(ret, baseUrl);

        baseUrl = linkParser.getBaseLink(doc, baseUrl);

        EdgeDomain domain = baseUrl.domain;

        for (var atag : doc.getElementsByTag("a")) {
            var linkOpt = linkParser.parseLinkPermissive(baseUrl, atag);
            if (linkParser.shouldIndexLink(atag)) {
                linkOpt.ifPresent(lp::accept);
            }
            else {
                linkOpt
                        .filter(url -> linkParser.hasBinarySuffix(url.path.toLowerCase()))
                        .ifPresent(lp::acceptNonIndexable);
            }
        }
        for (var frame : doc.getElementsByTag("frame")) {
            linkParser.parseFrame(baseUrl, frame).ifPresent(lp::accept);
        }
        for (var frame : doc.getElementsByTag("iframe")) {
            linkParser.parseFrame(baseUrl, frame).ifPresent(lp::accept);
        }
        for (var meta : doc.select("meta[http-equiv=refresh]")) {
            linkParser.parseMetaRedirect(baseUrl, meta).ifPresent(lp::accept);
        }

        words.addAllSyntheticTerms(FileLinks.createFileLinkKeywords(lp, domain));
        words.addAllSyntheticTerms(FileLinks.createFileEndingKeywords(doc));
        words.addAllSyntheticTerms(createLinkKeywords(lp, domain));
    }

    private Set<String> createLinkKeywords(LinkProcessor lp, EdgeDomain domain) {
        final Set<String> linkTerms = new HashSet<>();

        for (var fd : lp.getForeignDomains()) {
            linkTerms.add("links:"+fd.toString().toLowerCase());
            linkTerms.add("links:"+fd.getTopDomain().toLowerCase());
        }

        // Add keyword terms for the first 128 external links, with no prefix
        for (EdgeUrl link : lp.getSeenUrls()) {
            if (linkTerms.size() > 128) break;
            if (domain.hasSameTopDomain(link.domain)) continue;

            linkTerms.add(link.toString());
        }

        return linkTerms;
    }

    private DocumentFormat getDocumentFormat(Document doc) {
        DocumentFormat format = HtmlStandardExtractor.parseDocType(doc.documentType());
        if (DocumentFormat.UNKNOWN.equals(format)) {
            return HtmlStandardExtractor.sniffHtmlStandard(doc);
        }
        return format;
    }

    private int getLength(Document doc) {
        var mlv = new MeasureLengthVisitor();
        doc.traverse(mlv);
        return mlv.length;
    }

}
