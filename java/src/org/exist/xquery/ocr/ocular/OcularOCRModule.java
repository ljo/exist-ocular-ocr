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

import org.exist.xquery.AbstractInternalModule;
import org.exist.xquery.FunctionDef;

import java.util.List;
import java.util.Map;

/**
 * Integrates the Ocular OCR library.
 *
 * @author ljo
 */
public class OcularOCRModule extends AbstractInternalModule {

    public final static String NAMESPACE_URI = "http://exist-db.org/xquery/ocular-ocr";
    public final static String PREFIX = "ocr";

    public final static FunctionDef[] functions = {
        new FunctionDef(InitializeLanguageModel.signatures[0], InitializeLanguageModel.class),
        new FunctionDef(InitializeLanguageModel.signatures[1], InitializeLanguageModel.class),
        new FunctionDef(InitializeFont.signatures[0], InitializeFont.class),
        new FunctionDef(InitializeGlyphSubstitutionModel.signatures[0], InitializeGlyphSubstitutionModel.class),
        new FunctionDef(TrainFont.signatures[0], TrainFont.class),
        new FunctionDef(Transcribe.signatures[0], Transcribe.class)
    };

    public OcularOCRModule(Map<String, List<? extends Object>> parameters) {
        super(functions, parameters, false);
    }

    @Override
    public String getNamespaceURI() {
        return NAMESPACE_URI;
    }

    @Override
    public String getDefaultPrefix() {
        return PREFIX;
    }

    @Override
    public String getDescription() {
        return "OCR module using Ocular OCR library";
    }

    @Override
    public String getReleaseVersion() {
        return null;
    }
}
