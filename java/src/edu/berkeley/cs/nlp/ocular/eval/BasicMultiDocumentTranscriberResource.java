package edu.berkeley.cs.nlp.ocular.eval;

import static edu.berkeley.cs.nlp.ocular.util.Tuple2.Tuple2;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Map;

import edu.berkeley.cs.nlp.ocular.data.Document;
import edu.berkeley.cs.nlp.ocular.eval.Evaluator.EvalSuffStats;
import edu.berkeley.cs.nlp.ocular.font.FontVarLineHeight;
import edu.berkeley.cs.nlp.ocular.gsm.GlyphSubstitutionModel;
import edu.berkeley.cs.nlp.ocular.lm.CodeSwitchLanguageModel;
import edu.berkeley.cs.nlp.ocular.model.CharacterTemplateVarLineHeight;
import edu.berkeley.cs.nlp.ocular.model.DecoderEMVarLineHeight;
import edu.berkeley.cs.nlp.ocular.model.em.DenseBigramTransitionModel;
import edu.berkeley.cs.nlp.ocular.model.transition.SparseTransitionModel.TransitionState;
import edu.berkeley.cs.nlp.ocular.train.FontTrainerResource;
import edu.berkeley.cs.nlp.ocular.util.Tuple2;
import indexer.Indexer;

import org.exist.util.io.Resource;

/**
 * Transcribe all document, write their results to files, and evaluate the results.
 * 
 * @author Dan Garrette (dhgarrette@gmail.com)
 */
public class BasicMultiDocumentTranscriberResource implements MultiDocumentTranscriberVarLineHeight {
	private List<Document> documents;
	private String inputDocPath;
	private String outputPath;
	private DecoderEMVarLineHeight decoderEM;
	private SingleDocumentEvaluatorAndOutputPrinter docOutputPrinterAndEvaluator;
	private Indexer<String> charIndexer;
	
	public BasicMultiDocumentTranscriberResource(
			List<Document> documents, String inputDocPath, String outputPath,
			DecoderEMVarLineHeight decoderEM,
			SingleDocumentEvaluatorAndOutputPrinter documentOutputPrinterAndEvaluator,
			Indexer<String> charIndexer) {
		this.documents = documents;
		this.inputDocPath = inputDocPath;
		this.outputPath = outputPath;
		this.decoderEM = decoderEM;
		this.docOutputPrinterAndEvaluator = documentOutputPrinterAndEvaluator;
		this.charIndexer = charIndexer;
	}

	public void transcribe(FontVarLineHeight font, CodeSwitchLanguageModel lm, GlyphSubstitutionModel gsm) {
		transcribe(0, 0, font, lm, gsm);
	}
	
	public void transcribe(int iter, int batchId, FontVarLineHeight font, CodeSwitchLanguageModel lm, GlyphSubstitutionModel gsm) {
		int numDocs = documents.size();
		CharacterTemplateVarLineHeight[] templates = FontTrainerResource.loadTemplates(font, charIndexer);
		DenseBigramTransitionModel backwardTransitionModel = new DenseBigramTransitionModel(lm);
		
		double totalJointLogProb = 0.0;
		List<Tuple2<String, Map<String, EvalSuffStats>>> allDiplomaticEvals = new ArrayList<Tuple2<String, Map<String, EvalSuffStats>>>();
		List<Tuple2<String, Map<String, EvalSuffStats>>> allNormalizedEvals = new ArrayList<Tuple2<String, Map<String, EvalSuffStats>>>();
		for (int docNum = 0; docNum < numDocs; ++docNum) {
			Document doc = documents.get(docNum);
			System.out.println((iter > 0 ? "Training iteration "+iter+", " : "") + (batchId > 0 ? "batch "+batchId+", " : "") + "Transcribing eval document "+(docNum+1)+" of "+numDocs+":  "+doc.baseName() + "    " + (new SimpleDateFormat("yyyy/MM/dd HH:mm:ss").format(Calendar.getInstance().getTime())));
			
			Tuple2<Tuple2<TransitionState[][], int[][]>, Double> decodeResults = decoderEM.computeEStep(doc, false, lm, gsm, templates, backwardTransitionModel);
			final TransitionState[][] decodeStates = decodeResults._1._1;
			final int[][] decodeWidths = decodeResults._1._2;
			totalJointLogProb += decodeResults._2;

			Tuple2<Map<String, EvalSuffStats>,Map<String, EvalSuffStats>> evals = docOutputPrinterAndEvaluator.evaluateAndPrintTranscription(iter, batchId, doc, decodeStates, decodeWidths, inputDocPath, outputPath);
			if (evals._1 != null) allDiplomaticEvals.add(Tuple2(doc.baseName(), evals._1));
			if (evals._2 != null) allNormalizedEvals.add(Tuple2(doc.baseName(), evals._2));
		}
		double avgLogProb = totalJointLogProb / numDocs;
		System.out.println("Iteration "+iter+", batch "+batchId+": eval avg joint log prob: " + avgLogProb);
		if (new Resource(inputDocPath).isDirectory()) {
			//Document doc = documents.get(0);
			//String fileParent = FileUtil.removeCommonPathPrefixOfParents(new File(inputDocPath), new File(doc.baseName()))._2;
			String preext = "eval";
			String outputFilenameBase = outputPath + "/all_transcriptions/" + new File(inputDocPath).getName() + "/" + preext;
			if (iter > 0) outputFilenameBase += "_iter-" + iter;
			if (batchId > 0) outputFilenameBase += "_batch-" + batchId;
			if (!allDiplomaticEvals.isEmpty())
				EvalPrinterResource.printEvaluation(allDiplomaticEvals, outputFilenameBase + "_diplomatic.txt");
			if (!allNormalizedEvals.isEmpty())
				EvalPrinterResource.printEvaluation(allNormalizedEvals, outputFilenameBase + "_normalized.txt");
		}
	}

}
