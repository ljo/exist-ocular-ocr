package edu.berkeley.cs.nlp.ocular.data;

import static edu.berkeley.cs.nlp.ocular.data.textreader.Charset.SPACE;

import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import edu.berkeley.cs.nlp.ocular.data.textreader.Charset;
import edu.berkeley.cs.nlp.ocular.image.ImageUtils;
import edu.berkeley.cs.nlp.ocular.image.ImageUtils.PixelType;
//import edu.berkeley.cs.nlp.ocular.image.Visualizer;
import edu.berkeley.cs.nlp.ocular.image.VisualizerBugfix;
import edu.berkeley.cs.nlp.ocular.preprocessing.Binarizer;
import edu.berkeley.cs.nlp.ocular.preprocessing.Cropper;
import edu.berkeley.cs.nlp.ocular.preprocessing.LineExtractorBugfix;
import edu.berkeley.cs.nlp.ocular.preprocessing.Straightener;
import edu.berkeley.cs.nlp.ocular.util.FileUtil;
import static edu.berkeley.cs.nlp.ocular.util.CollectionHelper.last;
import fileio.fResource;

import org.exist.util.io.Resource;

/**
 * A document that reads a file only as it is needed (and then stores
 * the contents in memory for later use).
 * 
 * @author Dan Garrette (dhgarrette@gmail.com)
 */
public abstract class LazyRawImageDocumentResource implements Document {
	private final String inputPath;
	private final int lineHeight;
	private final double binarizeThreshold;
	private final boolean crop;

	private PixelType[][][] observations = null;

	private String extractedLinesPath = null;

	private String[][] diplomaticTextLines = null;
	private boolean diplomaticTextLinesLoaded = false;
	private String[][] normalizedTextLines = null;
	private boolean normalizedTextLinesLoaded = false;
	private List<String> normalizedText = null;
	private boolean normalizedTextLoaded = false;
	private int numExtractIterations = 5;
	private int numExtractRestarts = 100;
        private final int extractLineHeight;

	public LazyRawImageDocumentResource(String inputPath, int lineHeight, double binarizeThreshold, boolean crop, String extractedLinesPath, int numExtractIterations, int numExtractRestarts, int extractLineHeight) {
		this.inputPath = inputPath;
		this.lineHeight = lineHeight;
		this.binarizeThreshold = binarizeThreshold;
		this.crop = crop;
		this.extractedLinesPath = extractedLinesPath;
		this.numExtractIterations = numExtractIterations;
		this.numExtractRestarts = numExtractRestarts;
		this.extractLineHeight = extractLineHeight;
	}

	final public PixelType[][][] loadLineImages() {
	  if (observations == null) { // file has already been loaded in this Ocular run
		    if (extractedLinesPath == null) { // no pre-extraction path given
		    	doLoadObservationsFromFile(); // load data from original file
		    }
		    else { // a pre-extraction path was given
		      if (extractionFilesPresent()) { // pre-extracted lines exist at the specified location
		      	doLoadObservationsFromLineExtractionFiles(); // load data from pre-extracted line files
		      }
		      else { // pre-extraction has not been done yet; do it now.
		      	doLoadObservationsFromFile(); // load data from original file
        		doWriteExtractedLines(); // write extracted lines to files so they don't have to be re-extracted next time
		      }
	      }
    }
	  return observations;
	}

	private void doLoadObservationsFromFile() {
		BufferedImage bi = doLoadBufferedImage();
		double[][] levels = ImageUtils.getLevels(bi);
		double[][] rotLevels = Straightener.straighten(levels);
		double[][] cropLevels = crop ? Cropper.crop(rotLevels, binarizeThreshold) : rotLevels;
		Binarizer.binarizeGlobal(binarizeThreshold, cropLevels);
		List<double[][]> lines = LineExtractorBugfix.extractLines(cropLevels, numExtractIterations, numExtractRestarts, extractLineHeight);
		observations = new PixelType[lines.size()][][];
		for (int i = 0; i < lines.size(); ++i) {
			observations[i] = imageToObservation(ImageUtils.makeImage(lines.get(i)));
		}
	}
	
	private void doLoadObservationsFromLineExtractionFiles() {
		System.out.println("Loading pre-extracted line images from " + leLineDir());
		final Pattern pattern = Pattern.compile("line(\\d+)\\." + ext());
		File[] lineImageFiles = new Resource(leLineDir()).listFiles(
// new FilenameFilter() {
// 			public boolean accept(File dir, String name) {
// 				return pattern.matcher(name).matches();
// 			}
// 		}
);
		if (lineImageFiles == null) throw new RuntimeException("lineImageFiles is null");
		if (lineImageFiles.length == 0) throw new RuntimeException("lineImageFiles.length == 0");
		Arrays.sort(lineImageFiles);

		observations = new PixelType[lineImageFiles.length][][];
		for (int i = 0; i < lineImageFiles.length; ++i) {
			Matcher m = pattern.matcher(lineImageFiles[i].getName());
			if (m.find() && Integer.valueOf(m.group(1)) != i) throw new RuntimeException("Trying to load lines from "+leLineDir()+" but the file for line "+i+" is missing (found "+m.group(1)+" instead).");
			String lineImageFile = fullLeLinePath(i);
			System.out.println("    Loading pre-extracted line from " + lineImageFile);
			try {
				observations[i] = imageToObservation(fResource.readImage(lineImageFile));
			}
			catch (Exception e) {
				throw new RuntimeException("Couldn't read line image from: " + lineImageFile, e);
			}
		}
	}
	
	private PixelType[][] imageToObservation(BufferedImage image) {
		if (lineHeight >= 0) {
			return ImageUtils.getPixelTypes(resampleImage(image, lineHeight));
		}
		else {
			return ImageUtils.getPixelTypes(image);
		}
	}

	private void doWriteExtractedLines() {
		String multilineExtractionImagePath = multilineExtractionImagePath();
		System.out.println("Writing file line-extraction image to: " + multilineExtractionImagePath);
		new Resource(multilineExtractionImagePath).getParentFile().mkdirs();
		fResource.writeImage(multilineExtractionImagePath, VisualizerBugfix.renderLineExtraction(observations));
		
		// Write individual line files
		new Resource(leLineDir()).mkdirs();
		for (int l = 0; l < observations.length; ++l) {
			PixelType[][] observationLine = observations[l];
			String linePath = fullLeLinePath(l);
			System.out.println("  Writing individual line-extraction image to: " + linePath);
			fResource.writeImage(linePath, VisualizerBugfix.renderLineExtraction(observationLine));
		}
	}
	
	private boolean extractionFilesPresent() {
		File f = new Resource(fullLeLinePath(0));
		System.out.println("Looking for extractions in ["+f+"]. "+(f.exists() ? "Found" : "Not found")+".");
		return f.exists();
	}
	
	private String[][] loadTextFile(Resource textFile, String name) {
		if (textFile.exists()) {
			System.out.println("Evaluation "+name+" text found at " + textFile);
			List<List<String>> textList = new ArrayList<List<String>>();
			try {
				//BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(textFile), "UTF-8"));
				BufferedReader in = textFile.getBufferedReader();
				while (in.ready()) {
					textList.add(Charset.readCharacters(in.readLine()));
				}
				in.close();
			}
			catch (IOException e) {
				throw new RuntimeException(e);
			}

			String[][] textLines = new String[textList.size()][];
			for (int i = 0; i < textLines.length; ++i) {
				List<String> line = textList.get(i);
				textLines[i] = line.toArray(new String[line.size()]);
			}
			return textLines;
		}
		else {
			System.out.println("No evaluation "+name+" text found at " + textFile + "  (This is only a problem if you were trying to provide a gold "+name+" transcription to check accuracy.)");
			return null;
		}
	}
	
	public String[][] loadDiplomaticTextLines() {
		if (!diplomaticTextLinesLoaded) {
			diplomaticTextLines = loadTextFile(new Resource(baseName().replaceAll("\\.[^.]*$", "") + ".txt"), "diplomatic");
		}
		diplomaticTextLinesLoaded = true;
		return diplomaticTextLines;
	}

	public String[][] loadNormalizedTextLines() {
		if (!normalizedTextLinesLoaded) {
			normalizedTextLines = loadTextFile(new Resource(baseName().replaceAll("\\.[^.]*$", "") + "_normalized.txt"), "normalized");
		}
		normalizedTextLinesLoaded = true;
		return normalizedTextLines;
	}

	public List<String> loadNormalizedText() {
		if (!normalizedTextLoaded) {
			String[][] normalizedTextLines = loadNormalizedTextLines();
			if (normalizedTextLines != null) {
				normalizedText = new ArrayList<String>();
				for (String[] lineChars : loadNormalizedTextLines()) {
					for (String c : lineChars) {
						if (SPACE.equals(c) && (normalizedText.isEmpty() || SPACE.equals(last(normalizedText)))) {
							// do nothing -- collapse spaces
						}
						else {
							normalizedText.add(c);
						}
					}
					if (!normalizedText.isEmpty() && !SPACE.equals(last(normalizedText))) {
						normalizedText.add(SPACE);
					}
				}
				if (SPACE.equals(last(normalizedText))) {
					normalizedText.remove(normalizedText.size()-1);
				}
			}
		}
		normalizedTextLoaded = true;
		return normalizedText;
	}

	private String multilineExtractionImagePath() { return fullLePreExt() + "." + ext(); }
	private String leLineDir() { return fullLePreExt() + "_" + ext(); }
	private String fileParent() { return FileUtil.removeCommonPathPrefixOfParents(new File(inputPath), file())._2; }
	private String fullLePreExt() { return extractedLinesPath + "/" + fileParent() + "/" + lineHeight  + "/" + preext() + "-line_extract"; }
	private String fullLeLinePath(int lineNum) { return String.format(leLineDir() + "/line%03d." + ext(), lineNum); }
	
	abstract protected File file();
	abstract protected BufferedImage doLoadBufferedImage();
	abstract protected String preext();
	abstract protected String ext();

public static BufferedImage resampleImage(BufferedImage image, final int height) {
		final double mult = height / ((double) image.getHeight());
		//Image unbufScaledImage = image.getScaledInstance((int)(mult * image.getWidth()), height, Image.SCALE_DEFAULT);

		BufferedImage scaledImage;
		if (mult < 0.5) {
		    Image unbufScaledImage = image.getScaledInstance((int)(mult * image.getWidth()), height, Image.SCALE_AREA_AVERAGING);
		    scaledImage = new BufferedImage(unbufScaledImage.getWidth(null), unbufScaledImage.getHeight(null), BufferedImage.TYPE_BYTE_GRAY);
		    Graphics g = scaledImage.createGraphics();
		    g.drawImage(unbufScaledImage, 0, 0, null);
		    g.dispose();
		} else {
		    scaledImage = new BufferedImage((int)(mult * image.getWidth()), height, BufferedImage.TYPE_BYTE_GRAY);
		    Graphics2D g = scaledImage.createGraphics();
		    if (mult > 1.0) {
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
			g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
		    } else {
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
		    }
		    g.drawImage(image, 0, 0, (int)(mult * image.getWidth()), height, null);
		    //g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		    g.dispose();
		}
		return scaledImage;
	}

}
