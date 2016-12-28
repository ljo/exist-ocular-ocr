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

import static edu.berkeley.cs.nlp.ocular.util.Tuple2.Tuple2;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import edu.berkeley.cs.nlp.ocular.data.textreader.BasicTextReader;
import edu.berkeley.cs.nlp.ocular.data.textreader.CharIndexer;
import edu.berkeley.cs.nlp.ocular.data.textreader.Charset;
import edu.berkeley.cs.nlp.ocular.data.textreader.ConvertLongSTextReader;
import edu.berkeley.cs.nlp.ocular.data.textreader.ExplicitCharacterSetTextReader;
import edu.berkeley.cs.nlp.ocular.data.textreader.RemoveDiacriticsTextReader;
import edu.berkeley.cs.nlp.ocular.data.textreader.ReplaceSomeTextReaderResource;
import edu.berkeley.cs.nlp.ocular.data.textreader.TextReader;
import edu.berkeley.cs.nlp.ocular.lm.BasicCodeSwitchLanguageModel;
import edu.berkeley.cs.nlp.ocular.lm.CodeSwitchLanguageModel;
import edu.berkeley.cs.nlp.ocular.lm.CorpusCounter;
import edu.berkeley.cs.nlp.ocular.lm.LanguageModel;
import edu.berkeley.cs.nlp.ocular.lm.NgramLanguageModel;
import edu.berkeley.cs.nlp.ocular.lm.NgramLanguageModel.LMType;
import edu.berkeley.cs.nlp.ocular.lm.SingleLanguageModel;
import edu.berkeley.cs.nlp.ocular.util.FileUtil;
import edu.berkeley.cs.nlp.ocular.util.Tuple2;
//import fig.Option;
//import fig.OptionsParser;
import fileio.fResource;
import indexer.HashMapIndexer;
import indexer.Indexer;

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
import org.exist.storage.lock.Lock.LockMode;
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

public class InitializeLanguageModel extends BasicFunction {

    public final static FunctionSignature signatures[] = {
	new FunctionSignature(
			      new QName("initialize-language-model-string", OcularOCRModule.NAMESPACE_URI, OcularOCRModule.PREFIX),
			      "Trains a language model. Returns the path to the language model document " +
			      "if successful otherwise the empty sequence.",
			      new SequenceType[] {
				  new FunctionParameterSequenceType("language-model-uri", Type.ANY_URI, Cardinality.EXACTLY_ONE,
								    "The path to the serialized language model. The binary resource " +
								    "will be stored within the database"),
				  new FunctionParameterSequenceType("text", Type.STRING, Cardinality.ONE_OR_MORE,
								    "String(s) of text to bulid the language model from"),
                                  new FunctionParameterSequenceType("configuration", Type.ELEMENT, Cardinality.EXACTLY_ONE,
                                                                    "The configuration, eg &lt;parameters&gt;&lt;param name='insertLongS' value='false'/&gt;&lt;param name='lmCharCount' value='0'/&gt;&lt;/parameters&gt;.")
			      },
			      new FunctionReturnSequenceType(Type.ELEMENT, Cardinality.EXACTLY_ONE,
							     "The path to the stored language model")
			      ),
	new FunctionSignature(
			      new QName("initialize-language-model-collection", OcularOCRModule.NAMESPACE_URI, OcularOCRModule.PREFIX),
			      "Trains a language model. Returns the path to the language model document " +
			      "if successful otherwise the empty sequence",
			      new SequenceType[] {
				  new FunctionParameterSequenceType("language-model-uri", Type.ANY_URI, Cardinality.EXACTLY_ONE,
								    "The path to the serialized language model. The binary resource " +
								    "will be stored within the database"),
                                  new FunctionParameterSequenceType("collection-uri", Type.ANY_URI, Cardinality.EXACTLY_ONE,
                                                                    "The collection hierarchy to create the instances out of"),
                                  new FunctionParameterSequenceType("qname", Type.QNAME, Cardinality.ZERO_OR_ONE,
                                                                    "The QName to restrict text contents to, e. g. xs:QName(\"tei:body\")"),
                                  new FunctionParameterSequenceType("configuration", Type.ELEMENT, Cardinality.EXACTLY_ONE,
                                                                    "The configuration, eg &lt;parameters&gt;&lt;param name='insertLongS' value='false'/&gt;&lt;param name='lmCharCount' value='0'/&gt;&lt;/parameters&gt;.")
			      },
			      new FunctionReturnSequenceType(Type.ELEMENT, Cardinality.EXACTLY_ONE,
							     "The path to the stored language model")
			      )
    };

    private static Path dataDir = null;
    private AnalyzeContextInfo cachedContextInfo;
    private QName qname = null; //new QName("body", "http://www.tei-c.org/ns/1.0", "tei");
    private List<String> textList = new ArrayList<String>();
    private Properties parameters = new Properties();

    //@Option(gloss = "Output LM file path.")
    private String outputLmPath = null; // Required.
    
    //@Option(gloss = "Path to the text files (or directory hierarchies) for training the LM.  For each entry, the entire directory will be recursively searched for any files that do not start with `.`.  For a multilingual (code-switching) model, give multiple comma-separated files with language names: \"english->texts/english/,spanish->texts/spanish/,french->texts/french/\".  Be sure to wrap the whole string with \"quotes\".)")
    private String inputTextPath = null; // Required.
	
    //@Option(gloss = "Prior probability of each language; ignore for uniform priors. Give multiple comma-separated language/prior pairs: \"english->0.7,spanish->0.2,french->0.1\". If spaces are used, be sure to wrap the whole string with \"quotes\".  Defaults to uniform priors. (Only relevant if multiple languages used.)")
    private String languagePriors = null;
    
    //@Option(gloss = "Prior probability of sticking with the same language when moving between words in a code-switch model transition model. (Only relevant if multiple languages used.)")
    private double pKeepSameLanguage = 0.999999;
    
    //@Option(gloss = "Paths to Alternate Spelling Replacement files. If just a simple path is given, the replacements will be applied to all languages.  For language-specific replacements, give multiple comma-separated language/path pairs: \"english->rules/en.txt,spanish->rules/sp.txt,french->rules/fr.txt\". If spaces are used, be sure to wrap the whole string with \"quotes\". Any languages for which no replacements are need can be safely ignored.")
    private String alternateSpellingReplacementPaths = null;
    
    //@Option(gloss = "Use separate character type for long s.")
    private boolean insertLongS = false;
    
    //@Option(gloss = "Remove diacritics?")
    private boolean removeDiacritics = false;
    
    //@Option(gloss = "A set of valid characters. If a character with a diacritic is found but not in this set, the diacritic will be dropped. Other excluded characters will simply be dropped. Ignore to allow all characters.")
    private Set<String> explicitCharacterSet = null;

    //@Option(gloss = "LM character n-gram length.")
    private int charN = 6;
	
    //@Option(gloss = "Exponent on LM scores.")
    private double lmPower = 4.0;
	
    //@Option(gloss = "Number of characters to use for training the LM.  Use 0 to indicate that the full training data should be used.  Default: Use all documents in full.")
    private long lmCharCount = 0;
        
    public InitializeLanguageModel(XQueryContext context, FunctionSignature signature) {
        super(context, signature);
    }

    @Override
    public void analyze(AnalyzeContextInfo contextInfo) throws XPathException {
        cachedContextInfo = new AnalyzeContextInfo(contextInfo);
        super.analyze(cachedContextInfo);
    }

    @Override
    public Sequence eval(Sequence[] args, Sequence contextSequence) throws XPathException {
        outputLmPath = args[0].getStringValue();

        if (isCalledAs("initialize-language-model-collection") && getSignature().getArgumentCount() == 4) {
	    inputTextPath = args[1].getStringValue();

	    if (!args[2].isEmpty()) {
		qname = ((QNameValue) args[2]).getQName();
	    }
	    textList = getCollectionText(inputTextPath, qname);

	    if (!args[3].isEmpty()) {
		parameters = ParametersExtractor.parseParameters(((NodeValue)args[3].itemAt(0)).getNode());
	    }
	} else {
	    inputTextPath = "<string-input>";
	    textList = getParameterValues(args[1]);
	    
	    if (!args[2].isEmpty()) {
		parameters = ParametersExtractor.parseParameters(((NodeValue)args[2].itemAt(0)).getNode());
	    }
	}

        for (String property : parameters.stringPropertyNames()) {
            if ("inserLongS".equals(property)) {
                String value = parameters.getProperty(property);
                insertLongS = Boolean.valueOf(value);
	    } else if ("pKeepSameLanguage".equals(property)) {
                String value = parameters.getProperty(property);
                pKeepSameLanguage = Double.valueOf(value);
	    } else if ("removeDiacritics".equals(property)) {
                String value = parameters.getProperty(property);
                removeDiacritics = Boolean.valueOf(value);
	    } else if ("charN".equals(property)) {
                String value = parameters.getProperty(property);
                charN = Integer.valueOf(value);
	    } else if ("lmPower".equals(property)) {
                String value = parameters.getProperty(property);
                lmPower = Double.valueOf(value);
	    } else if ("lmCharCount".equals(property)) {
                String value = parameters.getProperty(property);
                lmCharCount = Long.valueOf(value);
	    }
        }

        context.pushDocumentContext();

	Tuple2<Indexer<String>, List<Tuple2<Tuple2<String, TextReader>, Double>>> langIndexerAndLmData = makePathsReadersAndPriors();
	Indexer<String> langIndexer = langIndexerAndLmData._1;
	List<Tuple2<Tuple2<String, TextReader>, Double>> pathsReadersAndPriors = langIndexerAndLmData._2;

	Indexer<String> charIndexer = new CharIndexer();
	List<Tuple2<SingleLanguageModel, Double>> lmsAndPriors = makeMultipleSubLMs(pathsReadersAndPriors, charIndexer, langIndexer);
	charIndexer.lock();

	System.out.println("pKeepSameLanguage = " + pKeepSameLanguage);
	double priorSum = 0.0;
	for(Tuple2<SingleLanguageModel,Double> lmAndPrior: lmsAndPriors)
	    priorSum += lmAndPrior._2;
	StringBuilder priorsSb = new StringBuilder("Language priors: ");
	for(int langIndex = 0; langIndex < langIndexer.size(); ++langIndex) {
	    String language = langIndexer.getObject(langIndex);
	    priorsSb.append(language).append(" -> ").append(lmsAndPriors.get(langIndex)._2 / priorSum).append(", ");
	}
	System.out.println(priorsSb.substring(0, priorsSb.length() - 2));
	System.out.println("charN = " + charN);

	List<String> chars = new ArrayList<String>();
	for (String c : charIndexer.getObjects()) chars.add(c);
	Collections.sort(chars);
	System.out.println("ALL POSSIBLE CHARACTERS: " + chars);

	CodeSwitchLanguageModel codeSwitchLM = new BasicCodeSwitchLanguageModel(lmsAndPriors, charIndexer, langIndexer, pKeepSameLanguage, charN);
	System.out.println("writing LM to " + outputLmPath);
	writeLM(codeSwitchLM, outputLmPath);

        try {
        } finally {
            context.popDocumentContext();
        }
	return new StringValue(outputLmPath);
    }

    public Tuple2<Indexer<String>, List<Tuple2<Tuple2<String, TextReader>, Double>>> makePathsReadersAndPriors() {
	String inputTextPathString = inputTextPath;
	if (!inputTextPath.contains("->")) inputTextPathString = "NoLanguageNameGiven->" + inputTextPath; // repair "invalid" input
	Map<String, String> languagePathMap = new HashMap<String, String>();
	for (String part : inputTextPathString.split(",")) {
	    String[] subparts = part.split("->");
	    if (subparts.length != 2) throw new IllegalArgumentException("malformed lmPath argument: comma-separated part must be of the form \"LANGUAGE->PATH\", was: " + part);
	    String language = subparts[0].trim();
	    String filepath = subparts[1].trim();
	    languagePathMap.put(language, filepath);
	}

	Map<String, Double> languagePriorMap = new HashMap<String, Double>();
	if (languagePriors != null && !languagePriors.isEmpty()) {
	    for (String part : languagePriors.split(",")) {
		String[] subparts = part.split("->");
		if (subparts.length != 2) throw new IllegalArgumentException("malformed languagePriors argument: comma-separated part must be of the form \"LANGUAGE->PRIOR\", was: " + part);
		String language = subparts[0].trim();
		Double prior = Double.parseDouble(subparts[1].trim());
		languagePriorMap.put(language, prior);
	    }
	    if (!languagePathMap.keySet().equals(languagePriorMap.keySet()))
		throw new RuntimeException("-inputTextPath and -languagePriors do not have the same set of languages: " + languagePathMap.keySet() + " vs " + languagePriorMap.keySet());
	}
	else {
	    for (String language : languagePathMap.keySet())
		languagePriorMap.put(language, 1.0);
	}
		
	Map<String, String> languageAltSpellPathMap = new HashMap<String, String>();
	if (alternateSpellingReplacementPaths != null && !alternateSpellingReplacementPaths.isEmpty()) {
	    if (!alternateSpellingReplacementPaths.contains("->")) { // only one path, use for all languages
		String replacementsPath = alternateSpellingReplacementPaths;
		for (String language : languagePathMap.keySet()) {
		    languageAltSpellPathMap.put(language, replacementsPath);
		}
	    }
	    else {
		for (String part : alternateSpellingReplacementPaths.split(",")) {
		    String[] subparts = part.split("->");
		    if (subparts.length != 2) throw new IllegalArgumentException("malformed alternateSpellingReplacementPaths argument: comma-separated part must be of the form \"LANGUAGE->PATH\", was: " + part);
		    String language = subparts[0].trim();
		    String replacementsPath = subparts[1].trim();
		    if (!languagePathMap.keySet().contains(language)) throw new RuntimeException("Language '"+language+"' appears in the alternateSpellingReplacementPaths argument but not in inputTextPath ("+languagePathMap.keySet()+")");
		    languageAltSpellPathMap.put(language, replacementsPath);
		}
	    }
	}
		
	List<Tuple2<Tuple2<String, TextReader>, Double>> pathsReadersAndPriors = new ArrayList<Tuple2<Tuple2<String, TextReader>, Double>>();
	Indexer<String> langIndexer = new HashMapIndexer<String>();
	for (String language : languagePathMap.keySet()) {
	    String filepath = languagePathMap.get(language);
	    Double prior = languagePriorMap.get(language);
	    System.out.println("For language '" + language + "', using text in " + filepath + ", prior=" + prior
			       + (languageAltSpellPathMap.keySet().contains(language) ? ", alternate spelling replacement rules in " + languageAltSpellPathMap.get(language) : ""));
			
	    TextReader textReader = new BasicTextReader(Charset.BANNED_CHARS);
	    if (explicitCharacterSet != null && !explicitCharacterSet.isEmpty()) textReader = new ExplicitCharacterSetTextReader(textReader, explicitCharacterSet);
	    if (removeDiacritics) textReader = new RemoveDiacriticsTextReader(textReader);
	    if (insertLongS) textReader = new ConvertLongSTextReader(textReader);
	    if (languageAltSpellPathMap.keySet().contains(language)) textReader = handleReplacementRulesOption(textReader, languageAltSpellPathMap.get(language));
			
	    langIndexer.getIndex(language);
	    pathsReadersAndPriors.add(Tuple2(Tuple2(filepath, textReader), prior));
	}

	return Tuple2(langIndexer, pathsReadersAndPriors);
    }
	
    private TextReader handleReplacementRulesOption(TextReader textReader, String replacementsFilePath) {
	File replacementsFile = new Resource(replacementsFilePath);
	if (!replacementsFile.exists()) throw new RuntimeException("replacementsFile [" + replacementsFilePath + "] does not exist");
	List<Tuple2<Tuple2<List<String>, List<String>>, Integer>> rules = ReplaceSomeTextReaderResource.loadRulesFromFile(replacementsFilePath);
	for (Tuple2<Tuple2<List<String>, List<String>>, Integer> rule : rules)
	    System.out.println("    " + rule);
	return new ReplaceSomeTextReaderResource(rules, textReader);
    }

    private List<Tuple2<SingleLanguageModel, Double>> makeMultipleSubLMs(List<Tuple2<Tuple2<String, TextReader>, Double>> pathsReadersAndPriors, Indexer<String> charIndexer, Indexer<String> langIndexer) {
	List<Tuple2<SingleLanguageModel, Double>> lmsAndPriors = new ArrayList<Tuple2<SingleLanguageModel, Double>>();
	for (int langIndex = 0; langIndex < langIndexer.size(); ++langIndex) {
	    Tuple2<Tuple2<String, TextReader>, Double> pathsReaderAndPrior = pathsReadersAndPriors.get(langIndex);
	    String language = langIndexer.getObject(langIndex);
	    String filepath = pathsReaderAndPrior._1._1;
	    TextReader textReader = pathsReaderAndPrior._1._2;
	    System.out.println(language + " text reader: " + textReader);

	    CorpusCounter counter = new CorpusCounter(charN);
	    List<String> chars = readFileChars(filepath, textReader, lmCharCount > 0 ? lmCharCount : Long.MAX_VALUE);
	    System.out.println("  using " + chars.size() + " characters for " + language + " read from " + filepath);
	    counter.countChars(chars, charIndexer, 0);

	    Double prior = pathsReaderAndPrior._2;
	    Set<Integer> activeChars = counter.getActiveCharacters();

	    List<Tuple2<Integer,Integer>> reverseUnigramCounts = new ArrayList<Tuple2<Integer,Integer>>();
	    for (Map.Entry<Integer,Integer> entry : counter.getUnigramCounts().entrySet())
		reverseUnigramCounts.add(Tuple2(entry.getValue(),entry.getKey()));
	    Collections.sort(reverseUnigramCounts, new Tuple2.DefaultLexicographicTuple2Comparator<Integer,Integer>());
	    Collections.reverse(reverseUnigramCounts);
	    for (Tuple2<Integer,Integer> entry : reverseUnigramCounts) {
		System.out.println("    "+entry._1+"  "+charIndexer.getObject(entry._2));
		if (entry._1 < 10) activeChars.remove(entry._2); // remove low-count characters
	    }
	    for (String c : Charset.UNIV_PUNC) activeChars.add(charIndexer.getIndex(c));
			
	    List<String> langChars = new ArrayList<String>();
	    for (int i : activeChars)
		langChars.add(charIndexer.getObject(i));
	    Collections.sort(langChars);
	    System.out.println(language + ": " + langChars);
			
	    SingleLanguageModel lm = new NgramLanguageModel(charIndexer, counter.getCounts(), counter.getActiveCharacters(), LMType.KNESER_NEY, lmPower);
	    lmsAndPriors.add(Tuple2(lm, prior));
	}
		
	/*
	 *  Add alternate versions of the characters, but don't necessary 
	 *  associate them with any particular languages since they are not 
	 *  truly characters in that language.
	 */
	charIndexer.getIndex(Charset.LONG_S);
	for (String c : charIndexer.getObjects()) {
	    Tuple2<List<String>,String> originalEscapedDiacriticsAndLetter = Charset.escapeCharSeparateDiacritics(c);
	    String baseLetter = originalEscapedDiacriticsAndLetter._2;
	    if (Charset.CHARS_THAT_CAN_BE_DECORATED_WITH_AN_ELISION_TILDE.contains(c))
		charIndexer.getIndex(Charset.TILDE_ESCAPE + c);
	    if (Charset.CHARS_THAT_CAN_BE_DECORATED_WITH_AN_ELISION_TILDE.contains(baseLetter))
		charIndexer.getIndex(Charset.TILDE_ESCAPE + baseLetter);
	    charIndexer.getIndex(baseLetter);
	}
	for (Map.Entry<String,String> entry : Charset.LIGATURES.entrySet()) {
	    List<String> ligature = Charset.readCharacters(entry.getKey());
	    if (ligature.size() > 1) throw new RuntimeException("Ligature ["+entry.getKey()+"] has more than one character: "+ligature);
	    charIndexer.getIndex(ligature.get(0));
	    for (String c : Charset.readCharacters(entry.getValue()))
		charIndexer.getIndex(c);
	}
		
	charIndexer.lock();
	return lmsAndPriors;
    }

    private List<String> readFileChars(String filepath, TextReader textReader, long charsToTake) {
	List<String> allChars = new ArrayList<String>();
	outer: 
	for (String line : textList) {
	    for (String c: textReader.readCharacters(line + " ")) {
		// validate the character...
		Charset.escapeChar(c);
		Charset.unescapeChar(c);
		allChars.add(c);
		//System.out.println(c);
	    }
	    if (allChars.size() >= charsToTake) break outer;
	}
	return allChars;
    }
	
    public static LanguageModel readLM(String lmPath) {
	ObjectInputStream in = null;
	try {
	    Resource file = new Resource(lmPath);
	    if (!file.exists()) {
		throw new RuntimeException("Serialized LanguageModel file " + lmPath + " not found");
	    }
	    in = new ObjectInputStream(new GZIPInputStream(file.getInputStream()));
	    return (LanguageModel) in.readObject();
	} catch (Exception e) {
	    throw new RuntimeException(e);
	} finally {
	    if (in != null)
		try { in.close(); } catch (IOException e) { throw new RuntimeException(e); }
	}
    }

    public static CodeSwitchLanguageModel readCodeSwitchLM(String lmPath) {
	return (CodeSwitchLanguageModel) readLM(lmPath);
    }

    public static void writeLM(CodeSwitchLanguageModel lm, String lmPath) {
	ObjectOutputStream out = null;
	try {
	    Resource res = new Resource(lmPath);
	    res.getParentFile().mkdirs();
	    out = new ObjectOutputStream(new GZIPOutputStream(res.getOutputStream()));
	    out.writeObject(lm);
	} catch (Exception e) {
	    throw new RuntimeException(e);
	} finally {
	    if (out != null)
		try { out.close(); } catch (IOException e) { throw new RuntimeException(e); }
	}
    }

    private List<String> getCollectionText(String collection, final QName qname)  throws XPathException {
        DocumentSet docs = null;
        XmldbURI uri = null;
        try {
            MutableDocumentSet ndocs = new DefaultDocumentSet();
            uri = new AnyURIValue(collection).toXmldbURI();
            final Collection coll = context.getBroker().getCollection(uri);
            if (coll == null) {
                if (context.isRaiseErrorOnFailedRetrieval()) {
                    throw new XPathException("FODC0002: can not access collection '" + uri + "'");
                }
            } else {
                if (context.inProtectedMode())
                    {context.getProtectedDocs().getDocsByCollection(coll, ndocs);}
                else
                    {coll.allDocs(context.getBroker(), ndocs,
                                  true, context.getProtectedDocs());}
            }
            docs = ndocs;
        } catch (final XPathException e) { //From AnyURIValue constructor
            e.setLocation(line, column);
            throw new XPathException("FODC0002: " + e.getMessage());
        } catch(final PermissionDeniedException pde) {
            throw new XPathException("FODC0002: can not access collection '" + pde.getMessage() + "'");   
        }
        // iterate through all docs and create the node set
        final ArrayList<String> result = new ArrayList<String>(docs.getDocumentCount() + 20);
        Lock dlock;
        DocumentImpl doc;
        for (final Iterator<DocumentImpl> i = docs.getDocumentIterator(); i.hasNext();) {
            doc = i.next();
            dlock = doc.getUpdateLock();
            boolean lockAcquired = false;
            try {
                if (!context.inProtectedMode() && !dlock.hasLock()) {
                    dlock.acquire(LockMode.READ_LOCK);
                    lockAcquired = true;
                }
                DocumentImpl docImpl = new NodeProxy(doc).getOwnerDocument();
                DBBroker broker = context.getBroker();
                if (qname != null) {
                    NodeList nl = docImpl.getElementsByTagNameNS(qname.getNamespaceURI(), qname.getLocalPart());

                    for (int ei =0; ei < nl.getLength(); ei++) {
                        result.add(new String(broker.getNodeValue((ElementImpl) nl.item(ei), true).replaceAll("­\\s*", "")));
                    }
                    
                } else {
                    result.add(new String(broker.getNodeValue((ElementImpl) docImpl.getDocumentElement(), true)).replaceAll("­\\s*", ""));
                }


            } catch (final LockException e) {
                throw new XPathException(e.getMessage());
            } finally {
                if (lockAcquired)
                    {dlock.release(LockMode.READ_LOCK);}
            }
        }
        return result;
    }

    /**
     * The method <code>getParameterValues</code>
     *
     * @param parameter a <code>Sequence</code> value
     * @return a <code>List<String></code> value
     * @exception XPathException if an error occurs
     */
    public static List<String> getParameterValues(Sequence parameter) throws XPathException {
        final List<String> args = new ArrayList<String>();
        for (final SequenceIterator j = parameter.iterate(); j.hasNext();) {
            final Item next = j.nextItem();
            args.add(next.getStringValue());
        }
        return args;
    }

}
