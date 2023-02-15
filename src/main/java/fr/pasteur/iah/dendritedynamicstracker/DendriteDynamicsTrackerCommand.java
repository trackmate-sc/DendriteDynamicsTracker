/*-
 * #%L
 * A Fiji plugin to track the dynamics of dendrites in 2D time-lapse movies.
 * %%
 * Copyright (C) 2019 - 2021 Institut Pasteur
 * %%
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 * 
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 * 
 * 3. Neither the name of the Institut Pasteur / IAH nor the names of its contributors
 *    may be used to endorse or promote products derived from this software without
 *    specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT,
 * INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
 * BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
 * OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
 * OF THE POSSIBILITY OF SUCH DAMAGE.
 * #L%
 */
package fr.pasteur.iah.dendritedynamicstracker;

import static fiji.plugin.trackmate.gui.Icons.TRACKMATE_ICON;

import java.awt.Rectangle;
import java.util.HashMap;
import java.util.Set;

import javax.swing.JFrame;

import org.jgrapht.graph.DefaultWeightedEdge;
import org.scijava.ItemIO;
import org.scijava.app.StatusService;
import org.scijava.command.Command;
import org.scijava.command.ContextCommand;
import org.scijava.log.LogService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

import fiji.plugin.trackmate.Logger;
import fiji.plugin.trackmate.Model;
import fiji.plugin.trackmate.SelectionModel;
import fiji.plugin.trackmate.Settings;
import fiji.plugin.trackmate.Spot;
import fiji.plugin.trackmate.TrackMate;
import fiji.plugin.trackmate.TrackModel;
import fiji.plugin.trackmate.detection.ManualDetectorFactory;
import fiji.plugin.trackmate.features.edges.EdgeTargetAnalyzer;
import fiji.plugin.trackmate.features.track.TrackDurationAnalyzer;
import fiji.plugin.trackmate.features.track.TrackIndexAnalyzer;
import fiji.plugin.trackmate.gui.GuiUtils;
import fiji.plugin.trackmate.gui.displaysettings.DisplaySettings;
import fiji.plugin.trackmate.gui.displaysettings.DisplaySettingsIO;
import fiji.plugin.trackmate.gui.wizard.TrackMateWizardSequence;
import fiji.plugin.trackmate.gui.wizard.WizardSequence;
import fiji.plugin.trackmate.gui.wizard.descriptors.ConfigureViewsDescriptor;
import fiji.plugin.trackmate.tracking.LAPUtils;
import fiji.plugin.trackmate.tracking.TrackerKeys;
import fiji.plugin.trackmate.tracking.sparselap.SimpleSparseLAPTrackerFactory;
import fiji.plugin.trackmate.visualization.TrackMateModelView;
import fiji.plugin.trackmate.visualization.hyperstack.HyperStackDisplayer;
import fr.pasteur.iah.dendritedynamicstracker.SkeletonKeyPointsDetector.DetectionResults;
import fr.pasteur.iah.dendritedynamicstracker.trackmate.feature.JunctionIDAnalyzerFactory;
import fr.pasteur.iah.dendritedynamicstracker.trackmate.tracking.SkeletonEndPointTrackerFactory;
import ij.IJ;
import ij.ImagePlus;
import ij.gui.Roi;
import net.imagej.ops.OpService;
import net.imagej.ops.special.function.Functions;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;

@Plugin(type = Command.class, name = "Dendrite Dynamics Tracker", menuPath = "Plugins>Tracking>Dendrite Dynamics Tracker")
public class DendriteDynamicsTrackerCommand extends ContextCommand {

	private static final String[] PRUNNING_METHOD_STRINGS = new String[] {
			"No prunning",
			"Shortest branch",
			"Lowest intensity pixel",
			"Lowest intensity branch"
	};

	@Parameter
	private LogService log;

	@Parameter
	private StatusService status;

	@Parameter
	private OpService ops;

	@Parameter(type = ItemIO.INPUT)
	private ImagePlus imp = null;

	@Parameter(type = ItemIO.INPUT, label = "In what channel is the skeleton?")
	private int skeletonChannel = 2;

	@Parameter(type = ItemIO.INPUT, label = "In what channel is raw data?")
	private int dataChannel = 1;

	@Parameter(type = ItemIO.INPUT, label = "Max linking distance for junctions.")
	private double junctionMaxLinkingDistance = 5.;

	@Parameter(type = ItemIO.INPUT, label = "Max Frame Gap for junctions.")
	private int junctionMaxFrameGap = 2;

	@Parameter(label = "Cycle-prunning method.", choices = {
			"No prunning",
			"Shortest branch",
			"Lowest intensity pixel",
			"Lowest intensity branch"
	})
	private String cyclePrunningMethodStr = PRUNNING_METHOD_STRINGS[3];

	@Parameter(type = ItemIO.INPUT, label = "Max linking distance for end-points.")
	private double endPointMaxLinkingDistance = 5.;

	@Parameter(type = ItemIO.INPUT, label = "Max Frame Gap for end-points.")
	private int endPointMaxFrameGap = 2;

	@Parameter(type = ItemIO.INPUT, label = "Matched cost-factor for end-points.")
	private double matchedCostFactor = SkeletonEndPointTrackerFactory.DEFAULT_MATCHED_COST_FACTOR.doubleValue();

	@Parameter(type = ItemIO.INPUT, label = "Exclude dendrites found at the image borders?")
	private boolean pruneBorderDendrites = true;

	@Parameter(type = ItemIO.INPUT, label = "Merge junction tracks with end-results?")
	private boolean mergeJunctionTracks = false;

	@Parameter(type = ItemIO.INPUT, label = "Export branch lengths and statistics to CSV files?")
	private boolean exportToCSV = false;

	@Override
	public void run() {

		/*
		 * Detect junctions and end-points.
		 */

		final int prunningMethod = getPrunningMethod(cyclePrunningMethodStr);
		final SkeletonKeyPointsDetector skeletonKeyPointOp = (SkeletonKeyPointsDetector) Functions.unary(
				ops, SkeletonKeyPointsDetector.class, DetectionResults.class, ImagePlus.class,
				skeletonChannel, dataChannel, prunningMethod);

		final DetectionResults detectionResults = skeletonKeyPointOp.calculate(imp);
		if (null == detectionResults)
			return;

		/*
		 * Track junctions.
		 */

		status.showStatus("Tracking junctions.");
		final Model junctionModel = trackJunctions(
				detectionResults,
				imp,
				junctionMaxLinkingDistance,
				junctionMaxFrameGap);
		if (null == junctionModel)
			return;

		/*
		 * Track end-points.
		 */

		status.showStatus("Tracking end-points.");
		final TrackMate endPointTrackmate = trackEndPoints(
				detectionResults,
				junctionModel,
				imp,
				endPointMaxLinkingDistance,
				endPointMaxFrameGap,
				matchedCostFactor,
				mergeJunctionTracks);
		if (null == endPointTrackmate)
			return;

		/*
		 * Prune dendrites found at the border of the images.
		 */

		if (pruneBorderDendrites) {
			final Interval roi = getRoi2D(imp);
			final double[] calibration = new double[] {
					imp.getCalibration().pixelWidth,
					imp.getCalibration().pixelHeight };
			DendriteTrackFilter.pruneBorderTracks(endPointTrackmate.getModel(), roi, calibration);

		}

		/*
		 * Analyze results.
		 */

		status.showStatus("Analyzing dendrite tracks.");
		final DendriteTrackAnalysis dendriteTrackAnalysis = new DendriteTrackAnalysis(endPointTrackmate, junctionModel,
				detectionResults);
		if (!dendriteTrackAnalysis.checkInput() || !dendriteTrackAnalysis.process()) {
			log.error("Error while performing dendrite track analysis: " + dendriteTrackAnalysis.getErrorMessage());
			return;
		}

		/*
		 * Export to CSV files.
		 */

		if (exportToCSV) {
			final DendriteDynamicsCSVExporter exporter = new DendriteDynamicsCSVExporter(endPointTrackmate);
			if (!exporter.checkInput() || !exporter.process())
				log.error("Error while exporting results:\n" + exporter.getErrorMessage());

		}

		/*
		 * Display results.
		 */

		// Main objects.
		final Model model = endPointTrackmate.getModel();
		final SelectionModel selectionModel = new SelectionModel(model);
		final DisplaySettings displaySettings = DisplaySettingsIO.readUserDefault();

		// Main view.
		final TrackMateModelView displayer2 = new HyperStackDisplayer(model, selectionModel, imp, displaySettings);
		displayer2.render();

		// Wizard.
		final WizardSequence sequence = new TrackMateWizardSequence(endPointTrackmate, selectionModel, displaySettings);
		sequence.setCurrent(ConfigureViewsDescriptor.KEY);
		final JFrame frame = sequence.run("TrackMate on " + imp.getShortTitle());
		frame.setIconImage(TRACKMATE_ICON.getImage());
		GuiUtils.positionWindow(frame, imp.getWindow());
		frame.setVisible(true);
	}

	public static TrackMate trackEndPoints(
			final DetectionResults detectionResults,
			final Model junctionModel,
			final ImagePlus imp,
			final double endPointMaxLinkingDistance,
			final int endPointMaxFrameGap,
			final double matchedCostFactor,
			final boolean mergeJunctionTracks) {

		final Model endPointModel = new Model();
		endPointModel.setPhysicalUnits(imp.getCalibration().getUnits(), imp.getCalibration().getTimeUnit());
		endPointModel.setSpots(detectionResults.endPointSpots, false);

		final Settings endPointSettings = new Settings(imp);
		endPointSettings.detectorFactory = new ManualDetectorFactory<>();
		endPointSettings.trackerFactory = new SkeletonEndPointTrackerFactory();

		endPointSettings.addAllAnalyzers();

		endPointSettings.addSpotAnalyzerFactory(new JunctionIDAnalyzerFactory<>());
		endPointSettings.trackerSettings = new HashMap<>();
		endPointSettings.trackerSettings.put(TrackerKeys.KEY_LINKING_MAX_DISTANCE,
				Double.valueOf(endPointMaxLinkingDistance));
		endPointSettings.trackerSettings.put(TrackerKeys.KEY_ALTERNATIVE_LINKING_COST_FACTOR,
				Double.valueOf(TrackerKeys.DEFAULT_ALTERNATIVE_LINKING_COST_FACTOR));
		endPointSettings.trackerSettings.put(SkeletonEndPointTrackerFactory.KEY_MATCHED_COST_FACTOR,
				Double.valueOf(matchedCostFactor));

		endPointSettings.trackerSettings.put(TrackerKeys.KEY_ALLOW_GAP_CLOSING, Boolean.TRUE);
		endPointSettings.trackerSettings.put(TrackerKeys.KEY_GAP_CLOSING_MAX_FRAME_GAP,
				Integer.valueOf(endPointMaxFrameGap));
		endPointSettings.trackerSettings.put(TrackerKeys.KEY_GAP_CLOSING_MAX_DISTANCE,
				Double.valueOf(endPointMaxLinkingDistance));

		final TrackMate endPointTrackmate = new TrackMate(endPointModel, endPointSettings);
		if (!endPointTrackmate.checkInput() || !endPointTrackmate.process()) {
			IJ.error("Problem with tracking.", endPointTrackmate.getErrorMessage());
			return null;
		}

		/*
		 * Name each branch track.
		 */

		for (final Integer branchTrackID : endPointModel.getTrackModel().trackIDs(false))
			endPointModel.getTrackModel().setName(branchTrackID, "Branch_" + branchTrackID);

		/*
		 * Merge with junction results.
		 */

		if (mergeJunctionTracks)
			merge(endPointModel, junctionModel);

		endPointTrackmate.computeSpotFeatures(false);
		endPointTrackmate.computeEdgeFeatures(false);
		endPointTrackmate.computeTrackFeatures(false);

		return endPointTrackmate;
	}

	public static Model trackJunctions(
			final DetectionResults detectionResults,
			final ImagePlus imp,
			final double junctionMaxLinkingDistance,
			final int junctionMaxFrameGap) {

		final Model junctionModel = new Model();
		junctionModel.setPhysicalUnits(imp.getCalibration().getUnits(), imp.getCalibration().getTimeUnit());
		junctionModel.setSpots(detectionResults.junctionsSpots, false);

		final Settings junctionSettings = new Settings(imp);
		junctionSettings.detectorFactory = new ManualDetectorFactory<>();
		junctionSettings.trackerFactory = new SimpleSparseLAPTrackerFactory();
		junctionSettings.trackerSettings = LAPUtils.getDefaultLAPSettingsMap();
		junctionSettings.addEdgeAnalyzer(new EdgeTargetAnalyzer());
		junctionSettings.addTrackAnalyzer(new TrackIndexAnalyzer());
		junctionSettings.addTrackAnalyzer(new TrackDurationAnalyzer());
		junctionSettings.trackerSettings.put(TrackerKeys.KEY_LINKING_MAX_DISTANCE,
				Double.valueOf(junctionMaxLinkingDistance));

		junctionSettings.trackerSettings.put(TrackerKeys.KEY_ALLOW_GAP_CLOSING, Boolean.TRUE);
		junctionSettings.trackerSettings.put(TrackerKeys.KEY_GAP_CLOSING_MAX_FRAME_GAP,
				Integer.valueOf(junctionMaxFrameGap));
		junctionSettings.trackerSettings.put(TrackerKeys.KEY_GAP_CLOSING_MAX_DISTANCE,
				Double.valueOf(junctionMaxLinkingDistance));

		final TrackMate junctionTrackmate = new TrackMate(junctionModel, junctionSettings);
		if (!junctionTrackmate.execTracking()) {
			IJ.error("Problem with tracking.", junctionTrackmate.getErrorMessage());
			return null;
		}
		junctionTrackmate.computeSpotFeatures(false);
		junctionTrackmate.computeEdgeFeatures(false);
		junctionTrackmate.computeTrackFeatures(false);

		/*
		 * Assign to each end-point the track ID of the junction they match.
		 */

		final TrackModel junctionTrackModel = junctionModel.getTrackModel();
		for (final Spot endPoint : detectionResults.endPointSpots.iterable(true)) {
			final Spot junction = detectionResults.junctionMap.get(endPoint);
			final Integer junctionTrackID = junctionTrackModel.trackIDOf(junction);
			if (null == junctionTrackID) {
				endPoint.setName("no junction");
				continue;
			}

			endPoint.putFeature(JunctionIDAnalyzerFactory.FEATURE, Double.valueOf(junctionTrackID.doubleValue()));
			endPoint.setName("->" + junctionTrackID);
		}

		/*
		 * Name each junction track.
		 */

		for (final Integer junctionTrackID : junctionTrackModel.trackIDs(false))
			junctionTrackModel.setName(junctionTrackID, "Junction_" + junctionTrackID);

		return junctionModel;
	}

	private static final int getPrunningMethod(final String cyclePrunningMethodStr) {
		for (int i = 0; i < PRUNNING_METHOD_STRINGS.length; i++)
			if (PRUNNING_METHOD_STRINGS[i].equals(cyclePrunningMethodStr))
				return i;

		return 3;
	}

	private static void merge(final Model model, final Model modelToMerge) {
		final int nNewTracks = modelToMerge.getTrackModel().nTracks(true);
		final Logger logger = model.getLogger();

		int progress = 0;
		model.beginUpdate();

		int nNewSpots = 0;
		try {
			/*
			 * Add spots that are part of tracks.
			 */

			// To harvest the max Id.
			int maxID = -1;
			for (final int id : modelToMerge.getTrackModel().trackIDs(true)) {

				if (id > maxID)
					maxID = id;

				/*
				 * Add new spots built on the ones in the source.
				 */

				final Set<Spot> spots = modelToMerge.getTrackModel().trackSpots(id);
				final HashMap<Spot, Spot> mapOldToNew = new HashMap<>(spots.size());

				// We keep a reference to the new spot, needed below.
				Spot newSpot = null;
				for (final Spot oldSpot : spots) {
					newSpot = new Spot(oldSpot);
					for (final String feature : oldSpot.getFeatures().keySet())
						newSpot.putFeature(feature, oldSpot.getFeature(feature));

					mapOldToNew.put(oldSpot, newSpot);
					newSpot.setName("J" + id);
					model.addSpotTo(newSpot, oldSpot.getFeature(Spot.FRAME).intValue());
					nNewSpots++;
				}

				/*
				 * Link new spots from info in the file.
				 */

				final Set<DefaultWeightedEdge> edges = modelToMerge.getTrackModel().trackEdges(id);
				for (final DefaultWeightedEdge edge : edges) {
					final Spot oldSource = modelToMerge.getTrackModel().getEdgeSource(edge);
					final Spot oldTarget = modelToMerge.getTrackModel().getEdgeTarget(edge);
					final Spot newSource = mapOldToNew.get(oldSource);
					final Spot newTarget = mapOldToNew.get(oldTarget);
					final double weight = modelToMerge.getTrackModel().getEdgeWeight(edge);

					model.addEdge(newSource, newTarget, weight);
				}

				/*
				 * Put back track names
				 */

				final String trackName = modelToMerge.getTrackModel().name(id);
				final int newId = model.getTrackModel().trackIDOf(newSpot);
				model.getTrackModel().setName(newId, trackName);

				progress++;
				logger.setProgress((double) progress / nNewTracks);
			}

			/*
			 * Add lonely spots.
			 */

			maxID++;
			for (final Spot oldSpot : modelToMerge.getSpots().iterable(true)) {
				if (modelToMerge.getTrackModel().trackIDOf(oldSpot) != null)
					continue;

				// An awkward way to avoid spot ID conflicts after loading
				// two files
				final Spot newSpot = new Spot(oldSpot);
				for (final String feature : oldSpot.getFeatures().keySet())
					newSpot.putFeature(feature, oldSpot.getFeature(feature));

				newSpot.setName("JL" + maxID++);
				model.addSpotTo(newSpot, oldSpot.getFeature(Spot.FRAME).intValue());
				nNewSpots++;
			}

		} finally {
			model.endUpdate();
			logger.setProgress(0);
			logger.log("Imported " + nNewTracks + " tracks and " + nNewSpots + " spots.\n");
		}
	}

	private static Interval getRoi2D(final ImagePlus imp) {
		final long[] min = new long[2];
		final long[] max = new long[2];
		final Roi roi = imp.getRoi();
		if (null == roi) {
			min[0] = 0;
			min[1] = 0;
			max[0] = imp.getWidth() - 1;
			max[1] = imp.getHeight() - 1;
		} else {
			final Rectangle bounds = roi.getBounds();
			min[0] = bounds.x;
			min[1] = bounds.y;
			max[0] = bounds.x + bounds.width - 1;
			max[1] = bounds.y + bounds.height - 1;
		}
		return new FinalInterval(min, max);
	}
}
