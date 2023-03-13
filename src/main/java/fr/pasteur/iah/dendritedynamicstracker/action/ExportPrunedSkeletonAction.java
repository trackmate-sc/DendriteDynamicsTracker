/*-
 * #%L
 * TrackMate: your buddy for everyday tracking.
 * %%
 * Copyright (C) 2010 - 2023 TrackMate developers.
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */
package fr.pasteur.iah.dendritedynamicstracker.action;

import org.scijava.plugin.Plugin;

import fiji.plugin.trackmate.action.AbstractTMAction;
import fiji.plugin.trackmate.action.TrackMateAction;
import fiji.plugin.trackmate.action.TrackMateActionFactory;
import fiji.plugin.trackmate.gui.displaysettings.DisplaySettings;

import static fiji.plugin.trackmate.gui.Icons.LABEL_IMG_ICON;
import static fiji.plugin.trackmate.gui.Icons.TRACKMATE_ICON;

import java.awt.Frame;
import java.util.ArrayList;
import java.util.Objects;

import sc.fiji.analyzeSkeleton.*;
import ij.ImagePlus;
import ij.ImageStack;
import ij.plugin.Concatenator;
import ij.plugin.Duplicator;

import javax.swing.ImageIcon;

import fiji.plugin.trackmate.Logger;
import fiji.plugin.trackmate.Model;
import fiji.plugin.trackmate.TrackModel;
import fiji.plugin.trackmate.SelectionModel;
import fiji.plugin.trackmate.Spot;
import fiji.plugin.trackmate.SpotCollection;
import fiji.plugin.trackmate.TrackMate;
import fiji.plugin.trackmate.gui.displaysettings.DisplaySettings;

public class ExportPrunedSkeletonAction extends AbstractTMAction {

    public static final String NAME = "Prune untracked branches in new .tif.";

    public static final String KEY = "EXPORT_PRUNED_SKELETON";

    public static final String INFO_TEXT = "<html>"
            + "This action exports a version of the skeleton given to the <b>Dendrite Dynamics Tracker</b> after "
            + "pruning all branches whose tips were not incorporated in a track"
            + "</html>";

    @Override
    public void execute(final TrackMate trackmate, final SelectionModel selectionModel,
            final DisplaySettings displaySettings, final Frame gui) {
        final Logger logger = trackmate.getModel().getLogger();
        /*
         * Generate label image.
         */
        createPrunedSkeleton(trackmate.getModel(), trackmate.getSettings().imp, logger)
                .show();
    }

    /**
     * Creates a new label {@link ImagePlus} where the spots of the specified
     * model are painted as ellipsoids taken from their shape, with their track
     * ID as pixel value.
     *
     * @param model
     *                    the model from which we takes the spots to paint.
     * @param originalImp
     *                    a source image to read calibration, name and
     *                    dimension from.
     *                    The output image will have the same size that
     *                    of this source image, except for the number of
     *                    channels, which will be 1.
     * @param logger
     *                    a {@link Logger} instance, to report progress of
     *                    the export
     *                    process.
     *
     * @return a new {@link ImagePlus}.
     */
    private static final ImagePlus createPrunedSkeleton(
            final Model model,
            final ImagePlus originalImp,
            final Logger logger) {

        ImagePlus imp = originalImp.duplicate();
        double dx = originalImp.getCalibration().pixelWidth;
        double dy = originalImp.getCalibration().pixelHeight;
        double dz = originalImp.getCalibration().pixelDepth;

        // Get tracks and spots
        TrackModel trackModel = model.getTrackModel();
        SpotCollection spots = SpotCollection.fromCollection(trackModel.vertexSet());
        // print("Num. Spots after filtering by duration:" + spots.getNSpots(true));

        ///// Pruning of branches //////
        int nFrames = imp.getNFrames();
        ImagePlus skeleton = new Duplicator().run(imp, 2, 2, 1, 1, 1, nFrames);
        // print("Frames:" + nFrames);
        ImagePlus[] prunedFrames = new ImagePlus[nFrames];

        // Loop through frames, pruning terminal branches not in a track, from each
        for (int frameIndex = 1; frameIndex <= nFrames; frameIndex++) {
            logger.setProgress((double) frameIndex / (double) nFrames);
            // duplicate the current frame
            ImagePlus frame = new Duplicator().run(skeleton, 1, 1, 1, 1, frameIndex, frameIndex);

            // analyze skeleton
            AnalyzeSkeleton_ skel = new AnalyzeSkeleton_();
            skel.setup("", frame);
            SkeletonResult skelResult = skel.run(AnalyzeSkeleton_.NONE, false, false, null, true, false);

            // create copy of input image
            ImageStack outStack = frame.getStack();

            // get graphs (one per skeleton in the image)
            Graph[] graph = skelResult.getGraph();

            // list of end-points
            ArrayList<Point> endPoints = skelResult.getListOfEndPoints();

            // iterate through graphs
            for (int i = 0; i < graph.length; i++) {
                ArrayList<Edge> listEdges = graph[i].getEdges();

                // iterate through edges
                for (Edge e : listEdges) {
                    ArrayList<Point> p1 = e.getV1().getPoints();
                    ArrayList<Point> p2 = e.getV2().getPoints();

                    boolean v1End = endPoints.contains(p1.get(0));
                    boolean v2End = endPoints.contains(p2.get(0));
                    // if v1 or v2 are tips of skeleton
                    if (v1End || v2End) {
                        // Convert points to spots
                        Spot v1Position = new Spot(dx * p1.get(0).x, dy * p1.get(0).y, 0, 0, 0);
                        Spot v2Position = new Spot(dx * p2.get(0).x, dy * p2.get(0).y, 0, 0, 0);

                        // Find spot corresponding to points, null if none exist
                        Spot spotAtV1 = spots.getSpotAt(v1Position, frameIndex - 1, true);
                        Spot spotAtV2 = spots.getSpotAt(v2Position, frameIndex - 1, true);
                        if (Objects.isNull(spotAtV1) && Objects.isNull(spotAtV2)) {
                            if (v1End)
                                outStack.setVoxel(p1.get(0).x, p1.get(0).y, p1.get(0).z, 0);
                            if (v2End)
                                outStack.setVoxel(p2.get(0).x, p2.get(0).y, p2.get(0).z, 0);
                            for (Point p : e.getSlabs())
                                outStack.setVoxel(p.x, p.y, p.z, 0);
                        }
                    }
                }
            }
            prunedFrames[frameIndex - 1] = frame;
        }

        // Concatenate slices
        ImagePlus out = new Concatenator().run(prunedFrames);
        out.setTitle(originalImp.getShortTitle() + "_pruned");
        return out;
    }

    @Plugin(type = TrackMateActionFactory.class)
    public static class Factory implements TrackMateActionFactory {

        @Override
        public String getInfoText() {
            return INFO_TEXT;
        }

        @Override
        public String getKey() {
            return KEY;
        }

        @Override
        public TrackMateAction create() {
            return new ExportPrunedSkeletonAction();
        }

        @Override
        public ImageIcon getIcon() {
            return LABEL_IMG_ICON;
        }

        @Override
        public String getName() {
            return NAME;
        }
    }
}
