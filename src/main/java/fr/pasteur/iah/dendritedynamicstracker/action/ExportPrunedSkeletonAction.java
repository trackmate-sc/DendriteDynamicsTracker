/*-
 * #%L
 * A Fiji plugin to track the dynamics of dendrites in 2D time-lapse movies.
 * %%
 * Copyright (C) 2019 - 2023 Institut Pasteur
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
package fr.pasteur.iah.dendritedynamicstracker.action;

import org.scijava.plugin.Plugin;

import fiji.plugin.trackmate.Logger;
import fiji.plugin.trackmate.Model;
import fiji.plugin.trackmate.TrackModel;
import fiji.plugin.trackmate.SelectionModel;
import fiji.plugin.trackmate.Spot;
import fiji.plugin.trackmate.SpotCollection;
import fiji.plugin.trackmate.TrackMate;
import fiji.plugin.trackmate.action.AbstractTMAction;
import fiji.plugin.trackmate.action.TrackMateAction;
import fiji.plugin.trackmate.action.TrackMateActionFactory;
import fiji.plugin.trackmate.gui.displaysettings.DisplaySettings;

import static fiji.plugin.trackmate.gui.Icons.LABEL_IMG_ICON;

import java.awt.Frame;
import java.util.ArrayList;
import java.util.Objects;

import sc.fiji.analyzeSkeleton.*;
import ij.ImagePlus;
import ij.ImageStack;
import ij.plugin.Concatenator;
import ij.plugin.Duplicator;

import javax.swing.ImageIcon;

public class ExportPrunedSkeletonAction extends AbstractTMAction {

    public static final String NAME = "Prune untracked terminal branches in new .tif.";

    public static final String KEY = "EXPORT_PRUNED_SKELETON";

    public static final String INFO_TEXT = "<html>"
            + "This action generates a new version of the skeleton given to the <b>Dendrite Dynamics Tracker</b> in which "
            + "all terminal branches whose tips were not incorporated in a track have been pruned."
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
        ImagePlus out = Concatenator.run(prunedFrames);
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
