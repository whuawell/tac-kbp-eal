package com.bbn.kbp.events;

import com.bbn.bue.common.Finishable;
import com.bbn.bue.common.HasDocID;
import com.bbn.bue.common.Inspector;
import com.bbn.bue.common.evaluation.AggregateBinaryFScoresInspector;
import com.bbn.bue.common.evaluation.BinaryErrorLogger;
import com.bbn.bue.common.evaluation.BinaryFScoreBootstrapStrategy;
import com.bbn.bue.common.evaluation.BootstrapInspector;
import com.bbn.bue.common.evaluation.EquivalenceBasedProvenancedAligner;
import com.bbn.bue.common.evaluation.EvalPair;
import com.bbn.bue.common.evaluation.InspectionNode;
import com.bbn.bue.common.evaluation.InspectorTreeDSL;
import com.bbn.bue.common.evaluation.InspectorTreeNode;
import com.bbn.bue.common.evaluation.ProvenancedAlignment;
import com.bbn.bue.common.files.FileUtils;
import com.bbn.bue.common.parameters.Parameters;
import com.bbn.bue.common.symbols.Symbol;
import com.bbn.bue.common.symbols.SymbolUtils;
import com.bbn.kbp.events.ontology.EREToKBPEventOntologyMapper;
import com.bbn.kbp.events.ontology.SimpleEventOntologyMapper;
import com.bbn.kbp.events2014.DocumentSystemOutput2015;
import com.bbn.kbp.events2014.Response;
import com.bbn.kbp.events2014.ResponseLinking;
import com.bbn.kbp.events2014.ResponseSet;
import com.bbn.kbp.events2014.SystemOutputLayout;
import com.bbn.kbp.events2014.io.SystemOutputStore;
import com.bbn.kbp.linking.ExplicitFMeasureInfo;
import com.bbn.kbp.linking.LinkF1;
import com.bbn.nlp.corenlp.CoreNLPDocument;
import com.bbn.nlp.corenlp.CoreNLPParseNode;
import com.bbn.nlp.corenlp.CoreNLPXMLLoader;
import com.bbn.nlp.corpora.ere.EREArgument;
import com.bbn.nlp.corpora.ere.EREDocument;
import com.bbn.nlp.corpora.ere.EREEntity;
import com.bbn.nlp.corpora.ere.EREEntityArgument;
import com.bbn.nlp.corpora.ere.EREEntityMention;
import com.bbn.nlp.corpora.ere.EREEvent;
import com.bbn.nlp.corpora.ere.EREEventMention;
import com.bbn.nlp.corpora.ere.EREFillerArgument;
import com.bbn.nlp.corpora.ere.ERELoader;
import com.bbn.nlp.events.HasEventType;
import com.bbn.nlp.events.scoring.DocLevelEventArg;
import com.bbn.nlp.parsing.HeadFinders;

import com.google.common.base.Charsets;
import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.HashMultiset;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Multiset;
import com.google.common.io.Files;
import com.google.common.reflect.TypeToken;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import javax.annotation.Nullable;

import static com.bbn.bue.common.evaluation.InspectorTreeDSL.inspect;
import static com.bbn.bue.common.evaluation.InspectorTreeDSL.transformBoth;
import static com.bbn.bue.common.evaluation.InspectorTreeDSL.transformLeft;
import static com.bbn.bue.common.evaluation.InspectorTreeDSL.transformRight;
import static com.bbn.bue.common.evaluation.InspectorTreeDSL.transformed;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.Iterables.concat;
import static com.google.common.collect.Iterables.filter;

/**
 * Scores KBP 2015 event argument output against an ERE gold standard.  Scoring is in terms of
 * (Event Type, Event Role, Entity) tuples. This program is an experimental rough draft and has a
 * number of limitations: <ul> <li>We only handle arguments which are entity mentions; others are
 * ignored according to the ERE structure on the gold side and by filtering out a (currently
 * hardcoded) set of argument roles on the system side.</li> <i>We map system responses to entities
 * by looking for an entity which has a mention which shares the character offsets of the base
 * filler exactly either by itself or by its nominal head (given in ERE).  In the future we may
 * implement more lenient alignment strategies.</i> <li> Currently system responses which fail to
 * align to any entity at all are discarded rather than penalized.</li> </ul>
 */
public final class ScoreKBPAgainstERE {

  private static final Logger log = LoggerFactory.getLogger(ScoreKBPAgainstERE.class);

  private ScoreKBPAgainstERE() {
    throw new UnsupportedOperationException();
  }

  public static void main(String[] argv) {
    // we wrap the main method in this way to
    // ensure a non-zero return value on failure
    try {
      trueMain(argv);
    } catch (Exception e) {
      e.printStackTrace();
      System.exit(1);
    }
  }

  private static void trueMain(String[] argv) throws IOException {
    Parameters params = Parameters.loadSerifStyle(new File(argv[0]));
    log.info(params.dump());
    final ImmutableSet<Symbol> docIDsToScore = ImmutableSet.copyOf(
        FileUtils.loadSymbolList(params.getExistingFile("docIDsToScore")));
    final ImmutableMap<Symbol, File> goldDocIDToFileMap = FileUtils.loadSymbolToFileMap(
        Files.asCharSource(params.getExistingFile("goldDocIDToFileMap"), Charsets.UTF_8));
    final File outputDir = params.getCreatableDirectory("ereScoringOutput");
    final SystemOutputLayout outputLayout = SystemOutputLayout.ParamParser.fromParamVal(
        params.getString("outputLayout"));
    final SystemOutputStore outputStore =
        outputLayout.open(params.getExistingDirectory("systemOutput"));

    final ImmutableMap<Symbol, File> coreNLPProcessedRawDocs = FileUtils.loadSymbolToFileMap(
        Files.asCharSource(params.getExistingFile("coreNLPDocIDMap"), Charsets.UTF_8));
    final boolean relaxUsingCORENLP = params.getBoolean("relaxUsingCoreNLP");
    final boolean useExactMatchForCoreNLPRelaxation =
        relaxUsingCORENLP && params.getBoolean("useExactMatchForCoreNLPRelaxation");
    final CoreNLPXMLLoader coreNLPXMLLoader =
        CoreNLPXMLLoader.builder(HeadFinders.<CoreNLPParseNode>getEnglishPTBHeadFinder()).build();

    log.info("Scoring over {} documents", docIDsToScore.size());

    // on the gold side we take an ERE document as input
    final TypeToken<EREDocument> inputIsEREDoc = new TypeToken<EREDocument>() {
    };
    // on the test side we take an AnswerKey, but we bundle it with the gold ERE document
    // for use in alignment later
    final TypeToken<EREDocAndResponses> inputIsEREDocAndAnswerKey =
        new TypeToken<EREDocAndResponses>() {
        };

    final InspectionNode<EvalPair<EREDocument, EREDocAndResponses>>
        input = InspectorTreeDSL.pairedInput(inputIsEREDoc, inputIsEREDocAndAnswerKey);

    // these will extract the scoring tuples from the KBP system input and ERE docs, respectively
    // we create these here because we will call their .finish method()s
    // at the end to record some statistics about alignment failures,
    // so we need to keep references to them
    final ResponsesAndLinkingFromKBPExtractor responsesAndLinkingFromKBPExtractor =
        new ResponsesAndLinkingFromKBPExtractor(coreNLPProcessedRawDocs,
            coreNLPXMLLoader, relaxUsingCORENLP,
            useExactMatchForCoreNLPRelaxation);
    final ResponsesAndLinkingFromEREExtractor responsesAndLinkingFromEREExtractor =
        new ResponsesAndLinkingFromEREExtractor(EREToKBPEventOntologyMapper.create2015Mapping());

    // this sets it up so that everything fed to input will be scored in various ways
    setupScoring(input, responsesAndLinkingFromKBPExtractor, responsesAndLinkingFromEREExtractor,
        outputDir);

    final ERELoader loader = ERELoader.create();

    for (final Symbol docID : docIDsToScore) {
      final File ereFileName = goldDocIDToFileMap.get(docID);
      if (ereFileName == null) {
        throw new RuntimeException("Missing key file for " + docID);
      }
      final EREDocument ereDoc = loader.loadFrom(ereFileName);
      checkState(ereDoc.getDocId().equals(docID.asString()),
          "fetched document ID must be equal to stored");
      final Iterable<Response>
          responses = filter(outputStore.read(docID).arguments().responses(), bannedRolesFilter);
      final ResponseLinking linking =
          ((DocumentSystemOutput2015) outputStore.read(docID)).linking();
      linking.copyWithFilteredResponses(Predicates.in(ImmutableSet.copyOf(responses)));
      // feed this ERE doc/ KBP output pair to the scoring network
      input.inspect(EvalPair.of(ereDoc, new EREDocAndResponses(ereDoc, responses, linking)));

    }

    // trigger the scoring network to write its summary files
    input.finish();
    // log alignment failures
    responsesAndLinkingFromKBPExtractor.finish();
    responsesAndLinkingFromEREExtractor.finish();
  }

  private static final ImmutableSet<Symbol> BANNED_ROLES =
      SymbolUtils.setFrom("Time", "Crime", "Position",
          "Fine", "Sentence");
  private static final Predicate<Response> bannedRolesFilter = new Predicate<Response>() {
    @Override
    public boolean apply(@Nullable final Response response) {
      return !BANNED_ROLES.contains(response.role());
    }
  };

  private static Function<EvalPair<? extends Iterable<? extends DocLevelEventArg>, ? extends Iterable<? extends DocLevelEventArg>>, ProvenancedAlignment<DocLevelEventArg, DocLevelEventArg, DocLevelEventArg, DocLevelEventArg>>
      EXACT_MATCH_ALIGNER = EquivalenceBasedProvenancedAligner
      .forEquivalenceFunction(Functions.<DocLevelEventArg>identity())
      .asFunction();

  // this sets up a scoring network which is executed on every input
  private static void setupScoring(
      final InspectionNode<EvalPair<EREDocument, EREDocAndResponses>> input,
      final ResponsesAndLinkingFromKBPExtractor responsesAndLinkingFromKBPExtractor,
      final ResponsesAndLinkingFromEREExtractor responsesAndLinkingFromEREExtractor,
      final File outputDir) {
    final InspectorTreeNode<EvalPair<ResponsesAndLinking, ResponsesAndLinking>>
        inputAsResponsesAndLinking =
        transformRight(transformLeft(input, responsesAndLinkingFromEREExtractor),
            responsesAndLinkingFromKBPExtractor);
    // set up for event argument scoring in 2015 style
    eventArgumentScoringSetup(inputAsResponsesAndLinking, outputDir);
    // set up for linking scoring in 2015 style
    linkingScoringSetup(inputAsResponsesAndLinking, outputDir);
  }

  private static void eventArgumentScoringSetup(
      final InspectorTreeNode<EvalPair<ResponsesAndLinking, ResponsesAndLinking>>
          inputAsResponsesAndLinking, final File outputDir) {
    final InspectorTreeNode<EvalPair<ImmutableSet<DocLevelEventArg>, ImmutableSet<DocLevelEventArg>>>
        inputAsSetsOfScoringTuples =
        transformBoth(inputAsResponsesAndLinking, ResponsesAndLinking.argFunction);

    final InspectorTreeNode<ProvenancedAlignment<DocLevelEventArg, DocLevelEventArg, DocLevelEventArg, DocLevelEventArg>>
        alignmentNode = transformed(inputAsSetsOfScoringTuples, EXACT_MATCH_ALIGNER);

    // overall F score
    final AggregateBinaryFScoresInspector<Object, Object> scoreAndWriteOverallFScore =
        AggregateBinaryFScoresInspector.createOutputtingTo("aggregateF.txt", outputDir);
    inspect(alignmentNode).with(scoreAndWriteOverallFScore);

    // log errors
    final BinaryErrorLogger<HasDocID, HasDocID> logWrongAnswers = BinaryErrorLogger
        .forStringifierAndOutputDir(Functions.<HasDocID>toStringFunction(), outputDir);
    inspect(alignmentNode).with(logWrongAnswers);

    final BinaryFScoreBootstrapStrategy perEventBootstrapStrategy =
        BinaryFScoreBootstrapStrategy.createBrokenDownBy("EventType",
            HasEventType.ExtractFunction.INSTANCE, outputDir);
    final BootstrapInspector breakdownScoresByEventTypeWithBootstrapping =
        BootstrapInspector.forStrategy(perEventBootstrapStrategy, 1000, new Random(0));
    inspect(alignmentNode).with(breakdownScoresByEventTypeWithBootstrapping);
  }

  private static void linkingScoringSetup(
      final InspectorTreeNode<EvalPair<ResponsesAndLinking, ResponsesAndLinking>>
          inputAsResponsesAndLinking, final File outputDir) {
    final InspectorTreeNode<EvalPair<ImmutableSet<ImmutableSet<DocLevelEventArg>>, ImmutableSet<ImmutableSet<DocLevelEventArg>>>>
        linkingNode = transformRight(
        transformLeft(inputAsResponsesAndLinking, ResponsesAndLinking.linkingFunction),
        ResponsesAndLinking.linkingFunction);

    final InspectorTreeNode<EvalPair<ImmutableSet<ImmutableSet<DocLevelEventArg>>, ImmutableSet<ImmutableSet<DocLevelEventArg>>>>
        filteredNode =
        transformed(linkingNode, ScoreKBPAgainstERE.<DocLevelEventArg>restrictToLinkingFunction());
    final LinkingInspector linkingInspector =
        LinkingInspector.createOutputtingTo(new File(outputDir, "linkingF.txt"));
    inspect(filteredNode).with(linkingInspector);
  }

  private static <T> Function<Iterable<? extends Set<T>>, ImmutableSet<ImmutableSet<T>>> filterNestedElements(
      final Predicate<T> filter) {
    return new Function<Iterable<? extends Set<T>>, ImmutableSet<ImmutableSet<T>>>() {
      @Nullable
      @Override
      public ImmutableSet<ImmutableSet<T>> apply(@Nullable final Iterable<? extends Set<T>> sets) {
        final ImmutableSet.Builder<ImmutableSet<T>> ret = ImmutableSet.builder();
        for (final Set<T> s : sets) {
          ret.add(ImmutableSet.copyOf(Iterables.filter(s, filter)));
        }
        return ret.build();
      }
    };
  }

  private static <T> Function<EvalPair<ImmutableSet<ImmutableSet<T>>, ImmutableSet<ImmutableSet<T>>>, EvalPair<ImmutableSet<ImmutableSet<T>>, ImmutableSet<ImmutableSet<T>>>> restrictToLinkingFunction() {
    return new Function<EvalPair<ImmutableSet<ImmutableSet<T>>, ImmutableSet<ImmutableSet<T>>>, EvalPair<ImmutableSet<ImmutableSet<T>>, ImmutableSet<ImmutableSet<T>>>>() {
      @Nullable
      @Override
      public EvalPair<ImmutableSet<ImmutableSet<T>>, ImmutableSet<ImmutableSet<T>>> apply(
          @Nullable final EvalPair<ImmutableSet<ImmutableSet<T>>, ImmutableSet<ImmutableSet<T>>> input) {
        final ImmutableSet<ImmutableSet<T>> key =
            filterNestedElements(Predicates.in(ImmutableSet.copyOf(Iterables.concat(input.test()))))
                .apply(input.key());
        return EvalPair.of(key, input.test());
      }
    };
  }

  private static final class LinkingInspector implements
      Inspector<EvalPair<ImmutableSet<ImmutableSet<DocLevelEventArg>>, ImmutableSet<ImmutableSet<DocLevelEventArg>>>> {

    private final File outputFile;
    ExplicitFMeasureInfo counts = null;

    private LinkingInspector(final File outputFile) {
      this.outputFile = outputFile;
    }

    public static LinkingInspector createOutputtingTo(final File outputFile) {
      return new LinkingInspector(outputFile);
    }

    @Override
    public void inspect(
        final EvalPair<ImmutableSet<ImmutableSet<DocLevelEventArg>>, ImmutableSet<ImmutableSet<DocLevelEventArg>>> item) {
      checkArgument(ImmutableSet.copyOf(concat(item.test())).containsAll(
          ImmutableSet.copyOf(concat(item.key()))), "Must contain only answers in test set!");
      counts = LinkF1.create().score(item.key(), item.test());
    }

    @Override
    public void finish() throws IOException {
      checkNotNull(counts, "Inspect must be called before Finish!");
      final PrintWriter outputWriter = new PrintWriter(outputFile);
      outputWriter.println(counts.toString());
      outputWriter.close();
    }
  }

  private static final class ResponsesAndLinkingFromEREExtractor
      implements Function<EREDocument, ResponsesAndLinking>, Finishable {

    // for tracking things from the answer key discarded due to not being entity mentions
    private final Multiset<String> allGoldArgs = HashMultiset.create();
    private final Multiset<String> discarded = HashMultiset.create();
    private final SimpleEventOntologyMapper mapper;

    private ResponsesAndLinkingFromEREExtractor(final SimpleEventOntologyMapper mapper) {
      this.mapper = checkNotNull(mapper);
    }

    @Override
    public ResponsesAndLinking apply(final EREDocument doc) {
      final ImmutableSet.Builder<DocLevelEventArg> ret = ImmutableSet.builder();
      // every event mention argument within a hopper is linked
      final ImmutableSet.Builder<ImmutableSet<DocLevelEventArg>> linking = ImmutableSet.builder();
      for (final EREEvent ereEvent : doc.getEvents()) {
        final ImmutableSet.Builder<DocLevelEventArg> responseSet = ImmutableSet.builder();
        for (final EREEventMention ereEventMention : ereEvent.getEventMentions()) {
          for (final EREArgument ereArgument : ereEventMention.getArguments()) {
            final Symbol ereEventMentionType = Symbol.from(ereEventMention.getType());
            final Symbol ereEventMentionSubtype = Symbol.from(ereEventMention.getSubtype());
            final Symbol ereArgumentRole = Symbol.from(ereArgument.getRole());

            boolean skip = false;
            if (!mapper.eventType(ereEventMentionType).isPresent()) {
              log.debug("EventType {} is not known to the KBP ontology", ereEventMentionType);
              skip = true;
            }
            if (!mapper.eventRole(ereArgumentRole).isPresent()) {
              log.debug("EventRole {} is not known to the KBP ontology", ereArgumentRole);
              skip = true;
            }
            if (!mapper.eventSubtype(ereEventMentionSubtype).isPresent()) {
              log.debug("EventSubtype {} is not known to the KBP ontology", ereEventMentionSubtype);
              skip = true;
            }
            if (skip) {
              continue;
            }

            // type.subtype is Response format
            final String typeRoleKey = mapper.eventType(ereEventMentionType).get() +
                "." + mapper.eventSubtype(ereEventMentionSubtype).get() +
                "/" + mapper.eventRole(ereArgumentRole).get();
            allGoldArgs.add(typeRoleKey);

            if (ereArgument instanceof EREEntityArgument) {
              final EREEntityMention entityMention =
                  ((EREEntityArgument) ereArgument).entityMention();
              final Optional<EREEntity> containingEntity = doc.getEntityContaining(entityMention);
              checkState(containingEntity.isPresent(), "Corrupt ERE key input lacks "
                  + "entity for entity mention %s", entityMention);
              final DocLevelEventArg arg = DocLevelEventArg.create(Symbol.from(doc.getDocId()),
                  Symbol.from(mapper.eventType(ereEventMentionType).get() + "." +
                      mapper.eventSubtype(ereEventMentionSubtype).get()),
                  mapper.eventRole(ereArgumentRole).get(),
                  containingEntity.get().getID());
              ret.add(arg);
              responseSet.add(arg);
            } else if (ereArgument instanceof EREFillerArgument) {
              final EREFillerArgument filler = (EREFillerArgument) ereArgument;
              final DocLevelEventArg arg = DocLevelEventArg.create(Symbol.from(doc.getDocId()),
                  Symbol.from(mapper.eventType(ereEventMentionType).get() + "." +
                      mapper.eventSubtype(ereEventMentionSubtype).get()),
                  mapper.eventRole(ereArgumentRole).get(), filler.filler().getID());
              ret.add(arg);
              responseSet.add(arg);
            } else {
              throw new RuntimeException("Unknown ERE argument type " + ereArgument.getClass());
            }
          }
        }
        linking.add(responseSet.build());
      }
      return new EREResponsesAndLinking(ret.build(), linking.build());
    }

    @Override
    public void finish() throws IOException {
      log.info(
          "Of {} gold event arguments, {} were discarded as non-entities",
          allGoldArgs.size(), discarded.size());
      for (final String errKey : discarded.elementSet()) {
        if (discarded.count(errKey) > 0) {
          log.info("Of {} gold {} arguments, {} discarded ",
              +allGoldArgs.count(errKey), errKey, discarded.count(errKey));
        }
      }
    }
  }

  private static final class ResponsesAndLinkingFromKBPExtractor
      implements Function<EREDocAndResponses, ResponsesAndLinking>,
      Finishable {

    private Multiset<String> mentionAlignmentFailures = HashMultiset.create();
    private Multiset<String> numResponses = HashMultiset.create();
    private final ImmutableMap<Symbol, File> ereMapping;
    private final CoreNLPXMLLoader coreNLPXMLLoader;
    private final boolean relaxUsingCORENLP;
    private final boolean useExactMatchForCoreNLPRelaxation;

    public ResponsesAndLinkingFromKBPExtractor(final Map<Symbol, File> ereMapping,
        final CoreNLPXMLLoader coreNLPXMLLoader, final boolean relaxUsingCORENLP,
        final boolean useExactMatchForCoreNLPRelaxation) {
      this.ereMapping = ImmutableMap.copyOf(ereMapping);
      this.coreNLPXMLLoader = coreNLPXMLLoader;
      this.relaxUsingCORENLP = relaxUsingCORENLP;
      this.useExactMatchForCoreNLPRelaxation = useExactMatchForCoreNLPRelaxation;
    }

    public ResponsesAndLinking apply(final EREDocAndResponses input) {
      final ImmutableSet.Builder<DocLevelEventArg> ret = ImmutableSet.builder();
      final Iterable<Response> responses = input.responses();
      final EREDocument doc = input.ereDoc();
      final Symbol ereID = Symbol.from(doc.getDocId());
      final Optional<CoreNLPDocument> coreNLPDoc;
      final EREAligner ereAligner;

      try {
        coreNLPDoc = Optional.fromNullable(ereMapping.get(ereID)).isPresent() ? Optional
            .of(coreNLPXMLLoader.loadFrom(ereMapping.get(ereID)))
                                                                              : Optional.<CoreNLPDocument>absent();
        ereAligner = EREAligner
            .create(relaxUsingCORENLP, useExactMatchForCoreNLPRelaxation, doc, coreNLPDoc);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
      final ImmutableMap.Builder<Response, DocLevelEventArg> responseToDocLevelArg =
          ImmutableMap.builder();

      for (final Response response : responses) {
        numResponses.add(errKey(response));

        // there are too few instances of these to bother matching on type currently
        final ImmutableSet<EREEntity> candidateEntities = ereAligner.entitiesForResponse(response);
        if (candidateEntities.size() > 1) {
          log.warn(
              "Found {} candidate entities for base filler {}, using the first one!",
              candidateEntities.size(), response.baseFiller());
        }

        final EREEntity matchingEntity = Iterables.getFirst(candidateEntities, null);
        if (matchingEntity != null) {
          final DocLevelEventArg res =
              DocLevelEventArg.create(Symbol.from(doc.getDocId()), response.type(),
                  response.role(), matchingEntity.getID());
          ret.add(res);
          responseToDocLevelArg.put(response, res);
        } else {
          final ImmutableSet<EREFillerArgument> fillers = ereAligner.fillersForResponse(response);
          final EREFillerArgument filler = Iterables.getFirst(fillers, null);
          // there are too few instances of these to bother matching on type currently
          if (fillers.size() > 1) {
            log.warn("Found multiple {} matching fillers for {}", fillers.size(),
                response.baseFiller());
          }
          if (filler != null) {
            final DocLevelEventArg res = DocLevelEventArg
                .create(Symbol.from(doc.getDocId()), response.type(), response.role(),
                    filler.filler().getID());
            ret.add(res);
            responseToDocLevelArg.put(response, res);
          } else {
            mentionAlignmentFailures.add(errKey(response));
            log.warn("Neither entity nor filler match found for " + response.baseFiller());
          }
        }


      }
      return new KBPResponsesAndLinking(ImmutableSet.copyOf(input.responses()),
          responseToDocLevelArg.build(), input.linking());
    }

    public String errKey(Response r) {
      return r.type() + "/" + r.role();
    }

    public void finish() {
      log.info(
          "Of {} system responses, got {} mention alignment failures",
          numResponses.size(), mentionAlignmentFailures.size());
      for (final String errKey : numResponses.elementSet()) {
        if (mentionAlignmentFailures.count(errKey) > 0) {
          log.info("Of {} {} responses, {} mention alignment failures",
              +numResponses.count(errKey), errKey, mentionAlignmentFailures.count(errKey));
        }
      }
    }
  }
}

interface ResponsesAndLinking {

  ImmutableSet<DocLevelEventArg> args();

  ImmutableSet<ImmutableSet<DocLevelEventArg>> linking();

  Function<ResponsesAndLinking, ImmutableSet<DocLevelEventArg>> argFunction =
      new Function<ResponsesAndLinking, ImmutableSet<DocLevelEventArg>>() {
        @Nullable
        @Override
        public ImmutableSet<DocLevelEventArg> apply(
            @Nullable final ResponsesAndLinking responsesAndLinking) {
          return responsesAndLinking.args();
        }
      };

  Function<ResponsesAndLinking, ImmutableSet<ImmutableSet<DocLevelEventArg>>> linkingFunction =
      new Function<ResponsesAndLinking, ImmutableSet<ImmutableSet<DocLevelEventArg>>>() {
        @Nullable
        @Override
        public ImmutableSet<ImmutableSet<DocLevelEventArg>> apply(
            @Nullable final ResponsesAndLinking responsesAndLinking) {
          return responsesAndLinking.linking();
        }
      };
}

final class KBPResponsesAndLinking implements ResponsesAndLinking {

  final ImmutableSet<Response> originalResponses;
  final ImmutableMap<Response, DocLevelEventArg> responseToDocLevelEventArg;
  final ImmutableSet<ImmutableSet<DocLevelEventArg>> responseSets;

  KBPResponsesAndLinking(final ImmutableSet<Response> originalResponses,
      final ImmutableMap<Response, DocLevelEventArg> responseToDocLevelEventArg,
      final ResponseLinking responseLinking) {
    this.originalResponses = originalResponses;
    this.responseToDocLevelEventArg = responseToDocLevelEventArg;
    final ImmutableSet.Builder<ImmutableSet<DocLevelEventArg>> responseSetsB =
        ImmutableSet.builder();
    for (final ResponseSet rs : responseLinking.responseSets()) {
      final ImmutableSet.Builder<DocLevelEventArg> rsn = ImmutableSet.builder();
      for (final Response response : rs) {
        if (responseToDocLevelEventArg.containsKey(response)) {
          rsn.add(responseToDocLevelEventArg.get(response));
        }
      }
      responseSetsB.add(rsn.build());
    }
    this.responseSets = responseSetsB.build();
  }


  @Override
  public ImmutableSet<DocLevelEventArg> args() {
    return ImmutableSet.copyOf(responseToDocLevelEventArg.values());
  }

  @Override
  public ImmutableSet<ImmutableSet<DocLevelEventArg>> linking() {
    return responseSets;
  }
}

final class EREResponsesAndLinking implements ResponsesAndLinking {

  final ImmutableSet<DocLevelEventArg> args;
  final ImmutableSet<ImmutableSet<DocLevelEventArg>> linking;

  EREResponsesAndLinking(final Iterable<DocLevelEventArg> args,
      final Iterable<ImmutableSet<DocLevelEventArg>> linking) {
    this.args = ImmutableSet.copyOf(args);
    this.linking = ImmutableSet.copyOf(linking);
  }

  @Override
  public ImmutableSet<DocLevelEventArg> args() {
    return args;
  }

  @Override
  public ImmutableSet<ImmutableSet<DocLevelEventArg>> linking() {
    return linking;
  }
}


final class EREDocAndResponses {

  private final EREDocument ereDoc;
  private final Iterable<Response> responses;
  private final ResponseLinking linking;

  public EREDocAndResponses(final EREDocument ereDoc, final Iterable<Response> responses,
      final ResponseLinking linking) {
    this.ereDoc = checkNotNull(ereDoc);
    this.responses = checkNotNull(responses);
    this.linking = checkNotNull(linking);
  }

  public EREDocument ereDoc() {
    return ereDoc;
  }

  public Iterable<Response> responses() {
    return responses;
  }

  public ResponseLinking linking() {
    return linking;
  }
}
