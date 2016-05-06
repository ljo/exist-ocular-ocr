package edu.berkeley.cs.nlp.ocular.model.em;

/**
 * @author Taylor Berg-Kirkpatrick (tberg@eecs.berkeley.edu)
 */
public interface EmissionCacheInnerLoopVarLineHeight {
	public void startup(float[][] whiteTemplates, float[][] blackTemplates, int[] templateNumIndices, int[] templateIndicesOffsets, int minTemplateWidth, int maxTemplateWidth, int maxSequenceLength, int totalTemplateNumIndices, int lineHeight);
	public void shutdown();
	public void compute(final float[] scores, final float[] whiteObservations, final float[] blackObservations, final int sequenceLength);
	public int numOuterThreads();
	public int numPopulateThreads();
}
