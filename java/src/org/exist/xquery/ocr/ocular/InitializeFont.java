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
import java.io.InputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import edu.berkeley.cs.nlp.ocular.data.textreader.Charset;
import edu.berkeley.cs.nlp.ocular.font.FontVarLineHeight;
import edu.berkeley.cs.nlp.ocular.image.FontRenderer;
import edu.berkeley.cs.nlp.ocular.image.ImageUtils.PixelType;
import edu.berkeley.cs.nlp.ocular.lm.LanguageModel;
import edu.berkeley.cs.nlp.ocular.model.CharacterTemplateVarLineHeight;
//import fig.Option;
//import fig.OptionsParser;
import fileio.fResource;
import indexer.Indexer;
import threading.BetterThreader;

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
import org.exist.util.VirtualTempFile;
import org.exist.util.io.Resource;
import org.exist.xmldb.XmldbURI;
import org.exist.xquery.*;
import org.exist.xquery.modules.ModuleUtils;
import org.exist.xquery.value.*;

import org.xml.sax.SAXException;

import org.w3c.dom.NodeList;

public class InitializeFont extends BasicFunction {

    public final static FunctionSignature signatures[] = {
	new FunctionSignature(
			      new QName("initialize-font", OcularOCRModule.NAMESPACE_URI, OcularOCRModule.PREFIX),
			      "Initializes a font from the language model. Returns the path to the font document " +
			      "if successful, otherwise the empty sequence.",
			      new SequenceType[] {
				  new FunctionParameterSequenceType("language-model-uri", Type.ANY_URI, Cardinality.EXACTLY_ONE,
								    "The path to the serialized language model in the database"),
				  new FunctionParameterSequenceType("font-uri", Type.ANY_URI, Cardinality.EXACTLY_ONE,
								    "The path to the serialized initialized font based on the language model"),
                                  new FunctionParameterSequenceType("configuration", Type.ELEMENT, Cardinality.EXACTLY_ONE,
                                                                    "The configuration, eg &lt;parameters&gt;&lt;param name='allowedFontsPath' value='/db/apps/ocular-ocr/resources/init-font/blackletter-fonts.txt'/&gt;&lt;/parameters&gt;.")
			      },
			      new FunctionReturnSequenceType(Type.ANY_URI, Cardinality.ZERO_OR_ONE,
							     "The path to the stored initialized font")
			      )
    };

    private AnalyzeContextInfo cachedContextInfo;
    private Properties parameters = new Properties();
    private int lineHeight = CharacterTemplateVarLineHeight.LINE_HEIGHT;

    //@Option(gloss = "Path to the language model file (so that it knows which characters to create images for).")
    private String inputLmPath = null; // Required.

    //@Option(gloss = "Output font file path.")
    private String outputFontPath = null; // Required.

    //@Option(gloss = "Path to a file that contains a custom list of font names that may be used to initialize the font. The file should contain one font name per line. Default: Use all valid fonts found on the computer.")
    private String allowedFontsPath = null;

    //@Option(gloss = "Number of threads to use.")
    private int numFontInitThreads = 8;
	
    //@Option(gloss = "Max template width as fraction of text line height.")
    private double templateMaxWidthFraction = 1.0;

    //@Option(gloss = "Min template width as fraction of text line height.")
    private double templateMinWidthFraction = 0.0;

    //@Option(gloss = "Max space template width as fraction of text line height.")
    private double spaceMaxWidthFraction = 1.0;

    //@Option(gloss = "Min space template width as fraction of text line height.")
    private double spaceMinWidthFraction = 0.0;
   

    public InitializeFont(XQueryContext context, FunctionSignature signature) {
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
	outputFontPath = args[1].getStringValue();

	if (!args[2].isEmpty()) {
	    parameters = ModuleUtils.parseParameters(((NodeValue)args[2].itemAt(0)).getNode());
	}
        
        for (String property : parameters.stringPropertyNames()) {
            if ("allowedFontsPath".equals(property)) {
                String value = parameters.getProperty(property);
                allowedFontsPath = value;
	     } else if ("numFontInitThreads".equals(property)) {
                String value = parameters.getProperty(property);
                numFontInitThreads = Integer.valueOf(value);

	     } else if ("lineHeight".equals(property)) {
                String value = parameters.getProperty(property);
                lineHeight = Integer.valueOf(value);
	    }
	}

        context.pushDocumentContext();

	Set<String> allowedFonts = getAllowedFontsListFromFile();

	final LanguageModel lm = InitializeLanguageModel.readLM(inputLmPath);
	final Indexer<String> charIndexer = lm.getCharacterIndexer();
	final CharacterTemplateVarLineHeight[] templates = new CharacterTemplateVarLineHeight[charIndexer.size()];
	final PixelType[][][][] fontPixelData = FontRenderer.getRenderedFont(charIndexer, lineHeight, allowedFonts);
//		final PixelType[][][] fAndBarFontPixelData = buildFAndBarFontPixelData(charIndexer, fontPixelData);
	BetterThreader.Function<Integer,Object> func = new BetterThreader.Function<Integer,Object>(){public void call(Integer c, Object ignore){
		String currChar = charIndexer.getObject(c);
		if (!currChar.equals(Charset.SPACE)) {
		    templates[c] = new CharacterTemplateVarLineHeight(currChar, (float) templateMaxWidthFraction, (float) templateMinWidthFraction, lineHeight);
//				if (currChar.equals(Charset.LONG_S)) {
//					templates[c].initializeAndSetPriorFromFontData(fAndBarFontPixelData);
//				} else {
					templates[c].initializeAndSetPriorFromFontData(fontPixelData[c]);
//				}
		} else {
		    templates[c] = new CharacterTemplateVarLineHeight(Charset.SPACE, (float) spaceMaxWidthFraction, (float) spaceMinWidthFraction, lineHeight);
		}
	    }};
	BetterThreader<Integer,Object> threader = new BetterThreader<Integer,Object>(func, numFontInitThreads);
	for (int c=0; c<templates.length; ++c) threader.addFunctionArgument(c);
	threader.run();
	Map<String,CharacterTemplateVarLineHeight> charTemplates = new HashMap<String, CharacterTemplateVarLineHeight>();
	for (CharacterTemplateVarLineHeight template : templates) {
	    charTemplates.put(template.getCharacter(), template);
	}
	System.out.println("Writing intialized font to" + outputFontPath);
	InitializeFont.writeFont(new FontVarLineHeight(charTemplates), outputFontPath);

        try {
        } finally {
            context.popDocumentContext();
        }
	return new StringValue(outputFontPath);
    }

    private Set<String> getAllowedFontsListFromFile() {
	Set<String> allowedFonts = new HashSet<String>();
	System.out.println("Looking for allowed fonts");
	if (allowedFontsPath != null) {
	    for (String fontName : fResource.readLines(allowedFontsPath)) {
		allowedFonts.add(fontName);
		System.out.println("Adding allowed font: " + fontName);
	    }
	}
	return allowedFonts;
    }
	
//	private static PixelType[][][] buildFAndBarFontPixelData(Indexer<String> charIndexer, PixelType[][][][] fontPixelData) {
//		List<PixelType[][]> fAndBarFontPixelData = new ArrayList<PixelType[][]>();
//		if (charIndexer.contains("f")) {
//			int c = charIndexer.getIndex("f");
//			for (PixelType[][] datum : fontPixelData[c]) {
//				fAndBarFontPixelData.add(datum);
//			}
//		}
//		if (charIndexer.contains("|")) {
//			int c = charIndexer.getIndex("|");
//			for (PixelType[][] datum : fontPixelData[c]) {
//				fAndBarFontPixelData.add(datum);
//			}
//		}
//		return fAndBarFontPixelData.toArray(new PixelType[0][][]);
//	}
	
    public static FontVarLineHeight readFont(String fontPath) {
	ObjectInputStream in = null;
	try {
	    Resource file = new Resource(fontPath);
	    if (!file.exists()) {
		throw new RuntimeException("Serialized font file " + fontPath + " not found");
	    }
	    in = new ObjectInputStream(new GZIPInputStream(file.getInputStream()));
	    Object obj = in.readObject();
	    
	    { // TODO: For legacy font models...
		if (obj instanceof Map<?, ?>) 
		    return new FontVarLineHeight((Map<String, CharacterTemplateVarLineHeight>)obj);
	    }

	    return (FontVarLineHeight) obj;
	} catch (Exception e) {
	    throw new RuntimeException(e);
	} finally {
	    if (in != null)
		try { in.close(); } catch (IOException e) { throw new RuntimeException(e); }
	}
    }

    public static void writeFont(FontVarLineHeight font, String fontPath) {
	ObjectOutputStream out = null;
	try {
	    Resource res = new Resource(fontPath);
	    res.getParentFile().mkdirs();
	    out = new ObjectOutputStream(new GZIPOutputStream(res.getOutputStream()));
	    out.writeObject(font);
	} catch (Exception e) {
	    throw new RuntimeException(e);
	} finally {
	    if (out != null)
		try { out.close(); } catch (IOException e) { throw new RuntimeException(e); }
	}
    }

}
