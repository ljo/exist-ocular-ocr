/*
 *   exist-ocular-ocr: XQuery module to integrate the Ocular OCR 
 *   library with eXist-db.
 *   Copyright (C) 2016 ljo
 *
 *   This program is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   This program is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.exist.xquery.ocr.ocular;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import static edu.berkeley.cs.nlp.ocular.eval.BasicSingleDocumentEvaluatorAndOutputPrinterResource.diplomaticTranscriptionOutputFile;
import static edu.berkeley.cs.nlp.ocular.eval.BasicSingleDocumentEvaluatorAndOutputPrinterResource.makeOutputFilenameBase;
import static edu.berkeley.cs.nlp.ocular.main.FonttrainTranscribeSharedResource.EmissionCacheInnerLoopType;

import edu.berkeley.cs.nlp.ocular.data.Document;
import edu.berkeley.cs.nlp.ocular.data.LazyRawImageLoaderResource;
import edu.berkeley.cs.nlp.ocular.eval.BasicMultiDocumentTranscriberResource;
import edu.berkeley.cs.nlp.ocular.eval.BasicSingleDocumentEvaluatorAndOutputPrinterResource;
import edu.berkeley.cs.nlp.ocular.eval.MultiDocumentTranscriberVarLineHeight;
import edu.berkeley.cs.nlp.ocular.eval.SingleDocumentEvaluatorAndOutputPrinter;
import edu.berkeley.cs.nlp.ocular.font.FontVarLineHeight;
import edu.berkeley.cs.nlp.ocular.gsm.GlyphSubstitutionModel;
import edu.berkeley.cs.nlp.ocular.gsm.BasicGlyphSubstitutionModelResource.BasicGlyphSubstitutionModelFactory;
import edu.berkeley.cs.nlp.ocular.lm.CodeSwitchLanguageModel;
import edu.berkeley.cs.nlp.ocular.main.FonttrainTranscribeSharedResource;
import edu.berkeley.cs.nlp.ocular.main.NoDocumentsFoundException;
import edu.berkeley.cs.nlp.ocular.main.NoDocumentsToProcessException;
import edu.berkeley.cs.nlp.ocular.model.DecoderEMVarLineHeight;
import edu.berkeley.cs.nlp.ocular.model.CharacterTemplateVarLineHeight;
import edu.berkeley.cs.nlp.ocular.train.FontTrainerResource;
import edu.berkeley.cs.nlp.ocular.util.FileUtil;
//import fig.Option;
//import fig.OptionsParser;
import indexer.Indexer;
import fileio.fResource;

import org.exist.collections.Collection;
import org.exist.dom.persistent.BinaryDocument;
import org.exist.dom.persistent.DefaultDocumentSet;
import org.exist.dom.persistent.DocumentImpl;
import org.exist.dom.persistent.DocumentSet;
import org.exist.dom.persistent.ElementImpl;
import org.exist.dom.persistent.MutableDocumentSet;
import org.exist.dom.persistent.NodeProxy;
import org.exist.dom.QName;
import org.exist.dom.memtree.DocumentBuilderReceiver;
import org.exist.dom.memtree.MemTreeBuilder;
import org.exist.security.PermissionDeniedException;
import org.exist.storage.BrokerPool;
import org.exist.storage.DBBroker;
import org.exist.storage.lock.Lock;
import org.exist.storage.txn.TransactionManager;
import org.exist.storage.txn.Txn;
import org.exist.util.LockException;
import org.exist.util.MimeType;
import org.exist.util.ParametersExtractor;
import org.exist.util.VirtualTempFile;
import org.exist.util.io.Resource;
import org.exist.xmldb.XmldbURI;
import org.exist.xquery.*;
import org.exist.xquery.value.*;

import org.xml.sax.SAXException;

import org.w3c.dom.NodeList;


public class Transcribe extends BasicFunction {

    public final static FunctionSignature signatures[] = {
       	new FunctionSignature(
			      new QName("transcribe", OcularOCRModule.NAMESPACE_URI, OcularOCRModule.PREFIX),
			      "Transcribe documents from the language model and trained font. Returns the path to the font document " +
			      "if successful, otherwise the empty sequence.",
			      new SequenceType[] {
				  new FunctionParameterSequenceType("language-model-uri", Type.ANY_URI, Cardinality.EXACTLY_ONE,
								    "The path to the serialized language model in the database"),
				  new FunctionParameterSequenceType("trained-font-uri", Type.ANY_URI, Cardinality.EXACTLY_ONE,
								    "The path to the serialized trained font based on the language model"),
				  new FunctionParameterSequenceType("input-path-uri", Type.ANY_URI, Cardinality.EXACTLY_ONE,
								    "The path to an input document, pdf or image, or a collection of pdf-documents or images"),
				  new FunctionParameterSequenceType("output-path-uri", Type.ANY_URI, Cardinality.EXACTLY_ONE,
								    "The path to transcriptions and evalutation metrics"),
				  new FunctionParameterSequenceType("retrain-language-model-uri", Type.ANY_URI, Cardinality.ZERO_OR_ONE,
								    "The path to the stored  retrained language model or if no retraining is performed, the output path"),
                                  new FunctionParameterSequenceType("configuration", Type.ELEMENT, Cardinality.EXACTLY_ONE,
                                                                    "The configuration, eg &lt;parameters&gt;&lt;param name='numEMIters' value='3'/&gt;&lt;/parameters&gt;.")
			      },
			      new FunctionReturnSequenceType(Type.ANY_URI, Cardinality.ZERO_OR_ONE,
							     "The path to the stored transcribed document(s)")
			      )
    };

    private AnalyzeContextInfo cachedContextInfo;
    private Properties parameters = new Properties();
    private String language;
    private int lineHeight = CharacterTemplateVarLineHeight.LINE_HEIGHT;
    private int extractLineHeight = CharacterTemplateVarLineHeight.LINE_HEIGHT;
    private int numExtractIterations = 5;
    private int numExtractRestarts = 100;

    //@Option(gloss = "Path of the directory that contains the input document images. The entire directory will be recursively searched for any files that do not end in `.txt` (and that do not start with `.`).")
    private String inputPath = null; //"test_img";
    private String inputDocListPath = null; //"test_img";

    //@Option(gloss = "Number of documents (pages) to use. Ignore or use -1 to use all documents. Default: use all documents")
    private int numDocs = Integer.MAX_VALUE;

    //@Option(gloss = "Number of training documents (pages) to skip over.  Useful, in combination with -numDocs, if you want to break a directory of documents into several chunks.  Default: 0")
    private int numDocsToSkip = 0;

    //@Option(gloss = "Path to the language model file.")
    private String inputLmPath = null; //"lm/cs_lm.lmser";

    //@Option(gloss = "Path of the font initializer file.")
    private String initFontPath = null; //"font/init.fontser";

    //@Option(gloss = "If true, for each doc the outputPath will be checked for an existing transcription and if one is found then the document will be skipped.")
    private boolean skipAlreadyTranscribedDocs = false;
	
    //@Option(gloss = "If true, an exception will be thrown if all of the input documents have already been transcribed (and thus the job has nothing to do).  Ignored unless -skipAlreadyTranscribedDocs=true.")
    private boolean failIfAllDocsAlreadyTranscribed = false;
	

    //@Option(gloss = "Number of iterations of EM to use for font learning.  (Only relevant if updateFont is set to true.)  Default: 3")
    private int numEMIters = 3;
	
    //@Option(gloss = "Number of documents to process for each parameter update.  (Only relevant if updateFont is set to true.)  This is useful if you are transcribing a large number of documents, and want to have Ocular slowly improve the model as it goes, which you would achieve with trainFont=true and numEMIter=1 (though this could also be achieved by simply running a series of smaller font training jobs each with numEMIter=1, which each subsequent job uses the model output by the previous).  Default is to update only after each full pass over the document set.")
    private int updateDocBatchSize = Integer.MAX_VALUE;

    //@Option(gloss = "Should the counts from each batch accumulate with the previous batches, as opposed to each batch starting fresh?  Note that the counts will always be refreshed after a full pass through the documents.  (Only relevant if updateFont is set to true.)  Default: true")
    private boolean accumulateBatchesWithinIter = true;
	
    //@Option(gloss = "The minimum number of documents that may be used to make a batch for updating parameters.  If the last batch of a pass will contain fewer than this many documents, then lump them in with the last complete batch.  (Only relevant if updateFont is set to true, and updateDocBatchSize is used.)  Default is to always lump remaining documents in with the last complete batch.")
    private int minDocBatchSize = Integer.MAX_VALUE;

    //@Option(gloss = "When evaluation should be done during training (after each parameter update in EM), this is the path of the directory that contains the evaluation input document images. The entire directory will be recursively searched for any files that do not end in `.txt` (and that do not start with `.`). (Only relevant if updateFont is set to true.)")
    private String evalInputDocPath = null; // Do not evaluate during font training.

    //@Option(gloss = "When using -evalInputDocPath, this is the path of the directory where the evaluation line-extraction images should be read/written.  If the line files exist here, they will be used; if not, they will be extracted and then written here.  Useful if: 1) you plan to run Ocular on the same documents multiple times and you want to save some time by not re-extracting the lines, or 2) you use an alternate line extractor (such as Tesseract) to pre-process the document.  If ignored, the document will simply be read from the original document image file, and no line images will be written.")
    private String evalExtractedLinesPath = null; // Don't read or write line image files. 

    //@Option(gloss = "When using -evalInputDocPath, this is the number of documents that will be evaluated on. Ignore or use 0 to use all documents. Default: Use all documents in the specified path.")
    private int evalNumDocs = Integer.MAX_VALUE;

    //@Option(gloss = "When using -evalInputDocPath, on iterations in which we run the evaluation, should the evaluation be run after each batch, as determined by -updateDocBatchSize (in addition to after each iteration)?")
    private boolean evalBatches = false;

    //@Option(gloss = "Path of the directory that will contain output transcriptions.")
    private String outputPath = null; //"output_dir";

    //@Option(gloss = "Path of the directory where the line-extraction images should be read/written.  If the line files exist here, they will be used; if not, they will be extracted and then written here.  Useful if: 1) you plan to run Ocular on the same documents multiple times and you want to save some time by not re-extracting the lines, or 2) you use an alternate line extractor (such as Tesseract) to pre-process the document.  If ignored, the document will simply be read from the original document image file, and no line images will be written.")
    private String extractedLinesPath = null;
	
    //@Option(gloss = "Path to write the learned font file to. (Required if updateFont is set to true, otherwise ignored.)")
    private String outputFontPath = null; //"font/trained.fontser";

    //@Option(gloss = "Should the model allow glyph substitutions? This includes substituted letters as well as letter elisions.")
    private boolean allowGlyphSubstitution = false;
	
    // The following options are only relevant if allowGlyphSubstitution is set to "true".
	
    //@Option(gloss = "Path to the input glyph substitution model file. (Only relevant if allowGlyphSubstitution is set to true.) Default: Don't use a pre-initialized GSM. (Learn one from scratch).")
    private String inputGsmPath = null;

    //@Option(gloss = "Exponent on GSM scores.")
    private double gsmPower = 4.0;

    //@Option(gloss = "The prior probability of not-substituting the LM char. This includes substituted letters as well as letter elisions.")
    private double gsmNoCharSubPrior = 0.9;

    //@Option(gloss = "Should the GSM be allowed to elide letters even without the presence of an elision-marking tilde?")
    private boolean gsmElideAnything = false;

    //@Option(gloss = "Should the glyph substitution model be trained (or updated) along with the font? (Only relevant if allowGlyphSubstitution is set to true.)")
    private boolean updateGsm = false;

    //@Option(gloss = "Path to write the retrained glyph substitution model file to. Required if updateGsm is set to true, otherwise ignored.")
    private String outputGsmPath = null;

    //@Option(gloss = "The default number of counts that every glyph gets in order to smooth the glyph substitution model estimation.")
    private double gsmSmoothingCount = 1.0;

    //@Option(gloss = "gsmElisionSmoothingCountMultiplier.")
    private double gsmElisionSmoothingCountMultiplier = 100.0;

    //@Option(gloss = "Path to write the retrained language model file to. (Only relevant if retrainLM is set to true.)  Default: Don't write out the trained LM.")
    private String outputLmPath = null; //"lm/cs_trained.lmser";

    //@Option(gloss = "A language model to be used to assign diacritics to the transcription output.")
    private boolean allowLanguageSwitchOnPunct = true;

    //@Option(gloss = "Quantile to use for pixel value thresholding. (High values mean more black pixels.)")
    private double binarizeThreshold = 0.12;

    //@Option(gloss = "Crop pages?")
    private boolean crop = true;

    //@Option(gloss = "Scale all lines to have the same height?")
    private boolean uniformLineHeight = true;

    //@Option(gloss = "Use Markov chain to generate vertical offsets. (Slower, but more accurate. Turning on Markov offsets my require larger beam size for good results.)")
    private boolean markovVerticalOffset = false;

    //@Option(gloss = "Size of beam for Viterbi inference. (Usually in range 10-50. Increasing beam size can improve accuracy, but will reduce speed.)")
    private int beamSize = 10;

    //@Option(gloss = "Engine to use for inner loop of emission cache computation. DEFAULT: Uses Java on CPU, which works on any machine but is the slowest method. OPENCL: Faster engine that uses either the CPU or integrated GPU (depending on processor) and requires OpenCL installation. CUDA: Fastest method, but requires a discrete NVIDIA GPU and CUDA installation.")
    private EmissionCacheInnerLoopType emissionEngine = EmissionCacheInnerLoopType.DEFAULT;

    //@Option(gloss = "GPU ID when using CUDA emission engine.")
    private int cudaDeviceID = 0;

    //@Option(gloss = "Number of threads to use for LFBGS during m-step.")
    private int numMstepThreads = 8;

    //@Option(gloss = "Number of threads to use during emission cache compuation. (Only has effect when emissionEngine is set to DEFAULT.)")
    public int numEmissionCacheThreads = 8;

    //@Option(gloss = "Number of threads to use for decoding. (More thread may increase speed, but may cause a loss of continuity across lines.)")
    private int numDecodeThreads = 1;

    //@Option(gloss = "Number of lines that compose a single decode batch. (Smaller batch size can reduce memory consumption.)")
    private int decodeBatchSize = 32;

    //@Option(gloss = "Min horizontal padding between characters in pixels. (Best left at default value: 1.)")
    private int paddingMinWidth = 1;

    //@Option(gloss = "Max horizontal padding between characters in pixels (Best left at default value: 5.)")
    private int paddingMaxWidth = 5;

    public Transcribe(XQueryContext context, FunctionSignature signature) {
        super(context, signature);
    }

    @Override
    public void analyze(AnalyzeContextInfo contextInfo) throws XPathException {
        cachedContextInfo = new AnalyzeContextInfo(contextInfo);
        super.analyze(cachedContextInfo);
    }

    @Override
    public Sequence eval(Sequence[] args, Sequence contextSequence) throws XPathException {
        inputLmPath = args[0].getStringValue();
	initFontPath = args[1].getStringValue();
	inputPath = args[2].getStringValue();

	outputPath = args[3].getStringValue();

	if (!args[4].isEmpty()) {
	    outputLmPath = args[4].getStringValue();
	}

	if (outputPath != null) {
	    File outputDir = new Resource(outputPath);
	    if (!outputDir.exists()) outputDir.mkdirs();
	}

	if (!args[5].isEmpty()) {
	    parameters = ParametersExtractor.parseParameters(((NodeValue)args[5].itemAt(0)).getNode());
	}
        
        for (String property : parameters.stringPropertyNames()) {
            if ("numEMIters".equals(property)) {
                String value = parameters.getProperty(property);
                numEMIters = Integer.valueOf(value);
	    } else if ("updateDocBatchSize".equals(property)) {
                String value = parameters.getProperty(property);
                updateDocBatchSize = Integer.valueOf(value);
	    } else if ("numDocs".equals(property)) {
                String value = parameters.getProperty(property);
                numDocs = Integer.valueOf(value);
	    } else if ("numDocsToSkip".equals(property)) {
                String value = parameters.getProperty(property);
                numDocsToSkip = Integer.valueOf(value);
	    } else if ("binarizeThreshold".equals(property)) {
                String value = parameters.getProperty(property);
                binarizeThreshold = Double.valueOf(value);
	    } else if ("crop".equals(property)) {
                String value = parameters.getProperty(property);
                crop = Boolean.valueOf(value);
	    } else if ("uniformLineHeight".equals(property)) {
                String value = parameters.getProperty(property);
                uniformLineHeight = Boolean.valueOf(value);
	    } else if ("lineHeight".equals(property)) {
                String value = parameters.getProperty(property);
                lineHeight = Integer.valueOf(value);
	    } else if ("extractLineHeight".equals(property)) {
                String value = parameters.getProperty(property);
                extractLineHeight = Integer.valueOf(value);
	    } else if ("extractedLinesPath".equals(property)) {
                String value = parameters.getProperty(property);
                extractedLinesPath = value;
	    } else if ("numExtractIterations".equals(property)) {
                String value = parameters.getProperty(property);
                numExtractIterations = Integer.valueOf(value);
	    } else if ("numExtractRestarts".equals(property)) {
                String value = parameters.getProperty(property);
                numExtractRestarts = Integer.valueOf(value);
            } else if ("language".equals(property)) {
                String value = parameters.getProperty(property);
                language = value;
            } else if ("inputGsmPath".equals(property)) {
                String value = parameters.getProperty(property);
                inputGsmPath = value;
            } else if ("outputGsmPath".equals(property)) {
                String value = parameters.getProperty(property);
                outputGsmPath = value;
	    } else if ("allowGlyphSubstitution".equals(property)) {
                String value = parameters.getProperty(property);
                allowGlyphSubstitution = Boolean.valueOf(value);
	    } else if ("markovVerticalOffset".equals(property)) {
                String value = parameters.getProperty(property);
                markovVerticalOffset = Boolean.valueOf(value);
	    } else if ("beamSize".equals(property)) {
                String value = parameters.getProperty(property);
                beamSize = Integer.valueOf(value);
	    } else if ("numDecodeThreads".equals(property)) {
                String value = parameters.getProperty(property);
                numDecodeThreads = Integer.valueOf(value);
	    } else if ("numMstepThreads".equals(property)) {
                String value = parameters.getProperty(property);
                numMstepThreads = Integer.valueOf(value);
	    } else if ("decodeBatchSize".equals(property)) {
                String value = parameters.getProperty(property);
                decodeBatchSize = Integer.valueOf(value);
            } else if ("emissionEngine".equals(property)) {
                String value = parameters.getProperty(property);
                switch (value) {
		case "DEFAULT": 
		    emissionEngine = EmissionCacheInnerLoopType.DEFAULT;
		    break;
		case "OPENCL": 
		    emissionEngine = EmissionCacheInnerLoopType.OPENCL;
		    break;
		case "CUDA": 
		    emissionEngine = EmissionCacheInnerLoopType.CUDA;
		    break;
		}
	    } else if ("cudaDeviceID".equals(property)) {
                String value = parameters.getProperty(property);
                cudaDeviceID = Integer.valueOf(value);
            }
        }

        context.pushDocumentContext();

	if (numDocsToSkip < 0) throw new IllegalArgumentException("-numDocsToSkip must be >= 0.  Was "+numDocsToSkip+".");

	CodeSwitchLanguageModel initialLM = InitializeLanguageModel.readCodeSwitchLM(inputLmPath);
	FontVarLineHeight initialFont = InitializeFont.readFont(initFontPath);
	BasicGlyphSubstitutionModelFactory gsmFactory = FonttrainTranscribeSharedResource.makeGsmFactory(initialLM, gsmSmoothingCount, gsmElisionSmoothingCountMultiplier, gsmPower, outputGsmPath);
	GlyphSubstitutionModel initialGSM = FonttrainTranscribeSharedResource.loadInitialGSM(gsmFactory, inputGsmPath, allowGlyphSubstitution);

	Indexer<String> charIndexer = initialLM.getCharacterIndexer();
	Indexer<String> langIndexer = initialLM.getLanguageIndexer();

	DecoderEMVarLineHeight decoderEM = FonttrainTranscribeSharedResource.makeDecoder(charIndexer, emissionEngine, lineHeight, allowGlyphSubstitution, gsmNoCharSubPrior, gsmElideAnything, markovVerticalOffset, beamSize, numDecodeThreads, numMstepThreads, decodeBatchSize);

	boolean evalCharIncludesDiacritic = true;
	SingleDocumentEvaluatorAndOutputPrinter documentOutputPrinterAndEvaluator = new BasicSingleDocumentEvaluatorAndOutputPrinterResource(charIndexer, langIndexer, allowGlyphSubstitution, evalCharIncludesDiacritic);

	List<String> inputDocPathList = TrainFont.getInputDocPathList(inputPath, inputDocListPath);
	List<Document> inputDocuments = LazyRawImageLoaderResource.loadDocuments(inputDocPathList, extractedLinesPath, numDocs, numDocsToSkip, uniformLineHeight, lineHeight, binarizeThreshold, crop, numExtractIterations, numExtractRestarts, extractLineHeight);
	if (inputDocuments.isEmpty()) throw new NoDocumentsFoundException();

	String newInputDocPath = FileUtil.lowestCommonPath(inputDocPathList);
	if (skipAlreadyTranscribedDocs) {
	    int numInputDocsBeforeSkipping = inputDocuments.size();
	    for (Iterator<Document> itr = inputDocuments.iterator(); itr.hasNext(); ) {
		Document doc = itr.next();
		String docTranscriptionPath = diplomaticTranscriptionOutputFile(makeOutputFilenameBase(doc, newInputDocPath, outputPath));
		if (new Resource(docTranscriptionPath).exists()) {
		    System.out.println("  Skipping " + doc.baseName() + " since it was already transcribed: ["+docTranscriptionPath+"]");
		    itr.remove();
		}
	    }
	    if (inputDocuments.isEmpty()) {
		String msg = "The input path contains "+numInputDocsBeforeSkipping+" documents, but all have already been transcribed, so there is nothing remaining for this job to do.  (This is due to setting -skipAlreadyTranscribedDocs=true.)";
		if (failIfAllDocsAlreadyTranscribed) {
		    throw new NoDocumentsToProcessException(msg);
		} else {
		    System.out.println("WARNING: "+msg);
		}
	    }
	}

	if (outputFontPath != null) {
	    //
	    // Update the font as we transcribe
	    //
	    MultiDocumentTranscriberVarLineHeight evalSetEvaluator = FonttrainTranscribeSharedResource.makeEvalSetEvaluator(charIndexer, decoderEM, documentOutputPrinterAndEvaluator, evalInputDocPath, evalExtractedLinesPath, evalNumDocs, uniformLineHeight, lineHeight, binarizeThreshold, crop, numExtractIterations, numExtractRestarts, extractLineHeight, outputPath);
	    new FontTrainerResource().doFontTrainPass(0,
					inputDocuments,  
					initialFont, initialLM, initialGSM,
					outputFontPath, outputLmPath, outputGsmPath,
					decoderEM,
					gsmFactory, documentOutputPrinterAndEvaluator,
					0, updateDocBatchSize > 0 ? updateDocBatchSize : inputDocuments.size(), true, false,
					numMstepThreads,
					newInputDocPath, outputPath,
					evalSetEvaluator, Integer.MAX_VALUE, evalBatches);
	} else {
	    //
	    // Transcribe with fixed parameters
	    //
	    System.out.println("Transcribing input data      " + (new SimpleDateFormat("yyyy/MM/dd HH:mm:ss").format(Calendar.getInstance().getTime())));
	    MultiDocumentTranscriberVarLineHeight transcriber = new BasicMultiDocumentTranscriberResource(inputDocuments, newInputDocPath, outputPath, decoderEM, documentOutputPrinterAndEvaluator, charIndexer);
	    transcriber.transcribe(initialFont, initialLM, initialGSM);
	}
		
	System.out.println("Completed.");		
	
	try {
	} finally {
	    context.popDocumentContext();
	}

	if (outputLmPath != null) {
	    return new StringValue(outputLmPath);
	} else if (outputPath != null){
	    return new StringValue(outputPath);
	}
	return Sequence.EMPTY_SEQUENCE;
    }
}
