package edu.berkeley.cs.nlp.ocular.eval;

import edu.berkeley.cs.nlp.ocular.font.FontVarLineHeight;
import edu.berkeley.cs.nlp.ocular.gsm.GlyphSubstitutionModel;
import edu.berkeley.cs.nlp.ocular.lm.CodeSwitchLanguageModel;

/**
 * @author Dan Garrette (dhgarrette@gmail.com)
 */
public interface MultiDocumentTranscriberVarLineHeight {

	public void transcribe(FontVarLineHeight font, CodeSwitchLanguageModel lm, GlyphSubstitutionModel gsm);
	public void transcribe(int iter, int batchId, FontVarLineHeight font, CodeSwitchLanguageModel lm, GlyphSubstitutionModel gsm);
	
	/**
	 * No-op evaluator implementation
	 */
	public static class NoOpMultiDocumentTranscriber implements MultiDocumentTranscriberVarLineHeight {
		public void transcribe(FontVarLineHeight font, CodeSwitchLanguageModel lm, GlyphSubstitutionModel gsm) {}
		public void transcribe(int iter, int batchId, FontVarLineHeight font, CodeSwitchLanguageModel lm, GlyphSubstitutionModel gsm) {}
	}
}
