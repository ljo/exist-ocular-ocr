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

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Properties;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import edu.berkeley.cs.nlp.ocular.gsm.BasicGlyphSubstitutionModel.BasicGlyphSubstitutionModelFactory;
//import edu.berkeley.cs.nlp.ocular.sub.BasicGlyphSubstitutionModel.BasicGlyphSubstitutionModelFactory;
import edu.berkeley.cs.nlp.ocular.gsm.GlyphSubstitutionModel;
import edu.berkeley.cs.nlp.ocular.lm.CodeSwitchLanguageModel;
import edu.berkeley.cs.nlp.ocular.main.FonttrainTranscribeSharedResource;
import fig.Option;
import fig.OptionsParser;
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

public class InitializeGlyphSubstitutionModel extends BasicFunction {

    public final static FunctionSignature signatures[] = {
	new FunctionSignature(
			      new QName("initialize-glyph-substitution-model", OcularOCRModule.NAMESPACE_URI, OcularOCRModule.PREFIX),
			      "Initializes a glyph substitution model (gsm) from the language model. Returns the path to the initialized glyph substitution model document" +
			      "if successful, otherwise the empty sequence.",
			      new SequenceType[] {
				  new FunctionParameterSequenceType("language-model-uri", Type.ANY_URI, Cardinality.EXACTLY_ONE,
								    "The path to the serialized language model in the database"),
				  new FunctionParameterSequenceType("gsm-uri", Type.ANY_URI, Cardinality.EXACTLY_ONE,
								    "The path to the serialized initialized glyph substitution model (gsm) based on the language model"),
                                  new FunctionParameterSequenceType("configuration", Type.ELEMENT, Cardinality.EXACTLY_ONE,
                                                                    "The configuration, eg &lt;parameters&gt;&lt;param name='gsmSmoothingCount' value='1.0'/&gt;&lt;param name='gsmElisionSmoothingCountMultiplier' value='100.0'/&gt;&lt;param name='gsmPower' value='4.0'/&gt;&lt;/parameters&gt;.")
			      },
			      new FunctionReturnSequenceType(Type.ANY_URI, Cardinality.ZERO_OR_ONE,
							     "The path to the stored initialized glyph substitution model")
			      )
    };

    private AnalyzeContextInfo cachedContextInfo;
    private Properties parameters = new Properties();

    //@Option(gloss = "Path to the language model file (so that it knows which characters to create images for).")
    public String lmPath = null;

    //@Option(gloss = "Output GSM file path.")
    public String outputGsmPath = null;

    //@Option(gloss = "The default number of counts that every glyph gets in order to smooth the glyph substitution model estimation.")
    public double gsmSmoothingCount = 1.0;
	
    //@Option(gloss = "gsmElisionSmoothingCountMultiplier.")
    public double gsmElisionSmoothingCountMultiplier = 100.0;
	
    //@Option(gloss = "Exponent on GSM scores.")
    public double gsmPower = 4.0;


    public InitializeGlyphSubstitutionModel(XQueryContext context, FunctionSignature signature) {
        super(context, signature);
    }

    @Override
    public void analyze(AnalyzeContextInfo contextInfo) throws XPathException {
        cachedContextInfo = new AnalyzeContextInfo(contextInfo);
        super.analyze(cachedContextInfo);
    }

    @Override
    public Sequence eval(Sequence[] args, Sequence contextSequence) throws XPathException {
        lmPath = args[0].getStringValue();
	outputGsmPath = args[1].getStringValue();
	if (!args[2].isEmpty()) {
	    parameters = ParametersExtractor.parseParameters(((NodeValue)args[2].itemAt(0)).getNode());
	}
        
        for (String property : parameters.stringPropertyNames()) {
	    if ("gsmSmoothingCount".equals(property)) {
                String value = parameters.getProperty(property);
                gsmSmoothingCount = Double.valueOf(value);
	    } else if ("gsmElisionSmoothingCountMultiplier".equals(property)) {
                String value = parameters.getProperty(property);
                gsmElisionSmoothingCountMultiplier = Double.valueOf(value);
	    } else if ("gsmPower".equals(property)) {
                String value = parameters.getProperty(property);
                gsmPower = Double.valueOf(value);
	    }
	}

        context.pushDocumentContext();

	final CodeSwitchLanguageModel lm = InitializeLanguageModel.readCodeSwitchLM(lmPath);
	final Indexer<String> charIndexer = lm.getCharacterIndexer();
	final Indexer<String> langIndexer = lm.getLanguageIndexer();
	Set<Integer>[] activeCharacterSets = FonttrainTranscribeSharedResource.makeActiveCharacterSets(lm);

	// Fake stuff
	int minCountsForEvalGsm = 0;
	String outputPath = null;
		
	BasicGlyphSubstitutionModelFactory factory = new BasicGlyphSubstitutionModelFactory(
				gsmSmoothingCount, gsmElisionSmoothingCountMultiplier, 
				langIndexer, charIndexer, 
				activeCharacterSets, gsmPower, minCountsForEvalGsm, outputPath);

	GlyphSubstitutionModel gsm = factory.uniform();

	writeGSM(gsm, outputGsmPath);

        try {
        } finally {
            context.popDocumentContext();
        }
	return new StringValue(outputGsmPath);
    }

    public static GlyphSubstitutionModel readGSM(String gsmPath) {
	ObjectInputStream in = null;
	try {
	    Resource file = new Resource(gsmPath);
	    if (!file.exists()) {
		throw new RuntimeException("Serialized GlyphSubstitutionModel file " + gsmPath + " not found");
	    }
	    in = new ObjectInputStream(new GZIPInputStream(file.getInputStream()));
	    return (GlyphSubstitutionModel) in.readObject();
	} catch (Exception e) {
	    throw new RuntimeException(e);
	} finally {
	    if (in != null)
		try { in.close(); } catch (IOException e) { throw new RuntimeException(e); }
	}
    }

    public static void writeGSM(GlyphSubstitutionModel gsm, String gsmPath) {
	ObjectOutputStream out = null;
	try {
	    Resource gsmRes = new Resource(gsmPath);
	    gsmRes.getParentFile().mkdirs();
	    out = new ObjectOutputStream(new GZIPOutputStream(gsmRes.getOutputStream()));
	    out.writeObject(gsm);
	} catch (Exception e) {
	    throw new RuntimeException(e);
	} finally {
	    if (out != null)
		try { out.close(); } catch (IOException e) { throw new RuntimeException(e); }
	}
    }
}
