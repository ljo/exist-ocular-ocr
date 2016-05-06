package edu.berkeley.cs.nlp.ocular.output;

import java.io.File;
import java.util.List;

import edu.berkeley.cs.nlp.ocular.data.textreader.Charset;
import edu.berkeley.cs.nlp.ocular.gsm.GlyphChar;
import edu.berkeley.cs.nlp.ocular.gsm.GlyphChar.GlyphType;
import edu.berkeley.cs.nlp.ocular.model.transition.SparseTransitionModel.TransitionState;
import edu.berkeley.cs.nlp.ocular.util.FileUtil;
import fileio.fResource;
import indexer.Indexer;

import org.exist.xquery.modules.ModuleUtils;

/**
 * @author ljo@exist-db.org
 */
public class HocrOutputWriterResource {
	
	private Indexer<String> charIndexer;
	private Indexer<String> langIndexer;
	
	public HocrOutputWriterResource(Indexer<String> charIndexer, Indexer<String> langIndexer) {
		this.charIndexer = charIndexer;
		this.langIndexer = langIndexer;
	}

	public void write(int numLines, List<TransitionState>[] viterbiTransStates, String imgFilename, String outputFilenameBase) {
		String htmlOutputFilename = outputFilenameBase + ".html";
		
		if (langIndexer.size() > 1) {
		    System.out.println("Multiple languages being used ("+langIndexer.size()+"), so an html file is being generated to show language switching.");
		}

		StringBuffer outputBuffer = new StringBuffer();
		outputBuffer.append("<html xmlns=\"http://www.w3.org/1999/xhtml\" xml:lang=\"en\" lang=\"en\">\n");
		outputBuffer.append("<head><meta http-equiv=\"Content-Type\" content=\"text/html; charset=UTF-8\" /></head>\n");
		outputBuffer.append("<body><div class=\"ocr_page\" id=\"" + new File(outputFilenameBase).getName() + "\">\n");

		String[] colors = new String[] { "Black", "Red", "Blue", "Olive", "Orange", "Magenta", "Lime", "Cyan", "Purple", "Green", "Brown" };

		int prevLanguage = -1;
		for (int line = 0; line < numLines; ++line) {
		    outputBuffer.append("<div class=\"ocr_line\" id=\"l" + line + "\">\n");
		    for (TransitionState ts : viterbiTransStates[line]) {
			int lmChar = ts.getLmCharIndex();
			GlyphChar glyph = ts.getGlyphChar();
			int glyphChar = glyph.templateCharIndex;
			String sglyphChar = Charset.unescapeCharPrecomposedOnly(charIndexer.getObject(glyphChar));
			
			int currLanguage = ts.getLanguageIndex();
			if (currLanguage != prevLanguage) {
			    outputBuffer.append("<font color=\"" + colors[currLanguage+1] + "\">");
			}
			
			if (lmChar != glyphChar || glyph.glyphType != GlyphType.NORMAL_CHAR) {
			    String norm = Charset.unescapeCharPrecomposedOnly(charIndexer.getObject(lmChar));
			    String dipl = (glyph.glyphType == GlyphType.DOUBLED ? "2x"+sglyphChar : glyph.isElided() ? "" : sglyphChar);
			    outputBuffer.append("[" + norm + "/" + dipl + "]");
			} else {
			    outputBuffer.append(sglyphChar);
			}
			if (currLanguage != prevLanguage) {
			    outputBuffer.append("</font>");
			}
			prevLanguage = currLanguage;

		    }
		    outputBuffer.append("</div>\n");
		}

		outputBuffer.append("</div><img src=\"" + FileUtil.pathRelativeTo(imgFilename, new File(outputFilenameBase).getParent()) + "\" />\n");
		outputBuffer.append("</body></html>\n");
		String outputString = outputBuffer.toString();

		System.out.println("Writing hOCR/html output to " + htmlOutputFilename);
		fResource.writeString(htmlOutputFilename, outputString);
	}
	
}
