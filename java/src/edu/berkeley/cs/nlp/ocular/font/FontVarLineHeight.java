package edu.berkeley.cs.nlp.ocular.font;

import java.io.Serializable;
import java.util.Map;

import edu.berkeley.cs.nlp.ocular.model.CharacterTemplateVarLineHeight;

/**
 * @author Dan Garrette (dhgarrette@gmail.com)
 */
public class FontVarLineHeight implements Serializable {
	private static final long serialVersionUID = 1L;

	public final Map<String, CharacterTemplateVarLineHeight> charTemplates;

	public FontVarLineHeight(Map<String, CharacterTemplateVarLineHeight> charTemplates) {
		this.charTemplates = charTemplates;
	}
	
	public CharacterTemplateVarLineHeight get(String character) {
		return charTemplates.get(character);
	}

}
