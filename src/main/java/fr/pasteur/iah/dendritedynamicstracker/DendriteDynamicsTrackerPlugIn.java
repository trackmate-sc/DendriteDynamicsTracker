package fr.pasteur.iah.dendritedynamicstracker;

import java.util.List;

import fiji.plugin.trackmate.Model;
import fiji.plugin.trackmate.SelectionModel;
import fiji.plugin.trackmate.Settings;
import fiji.plugin.trackmate.Spot;
import fiji.plugin.trackmate.SpotCollection;
import fiji.plugin.trackmate.TrackMate;
import fiji.plugin.trackmate.detection.ManualDetectorFactory;
import fiji.plugin.trackmate.features.edges.EdgeTargetAnalyzer;
import fiji.plugin.trackmate.features.track.TrackDurationAnalyzer;
import fiji.plugin.trackmate.features.track.TrackIndexAnalyzer;
import fiji.plugin.trackmate.gui.GuiUtils;
import fiji.plugin.trackmate.gui.TrackMateGUIController;
import fiji.plugin.trackmate.gui.descriptors.ConfigureViewsDescriptor;
import fiji.plugin.trackmate.tracking.LAPUtils;
import fiji.plugin.trackmate.tracking.TrackerKeys;
import fiji.plugin.trackmate.tracking.sparselap.SimpleSparseLAPTrackerFactory;
import fiji.plugin.trackmate.visualization.PerTrackFeatureColorGenerator;
import fiji.plugin.trackmate.visualization.TrackColorGenerator;
import fiji.plugin.trackmate.visualization.TrackMateModelView;
import fiji.plugin.trackmate.visualization.hyperstack.HyperStackDisplayer;
import ij.IJ;
import ij.ImagePlus;
import ij.WindowManager;
import ij.gui.Roi;
import ij.plugin.Duplicator;
import ij.plugin.PlugIn;
import sc.fiji.analyzeSkeleton.AnalyzeSkeleton_;
import sc.fiji.analyzeSkeleton.Point;
import sc.fiji.analyzeSkeleton.SkeletonResult;

public class DendriteDynamicsTrackerPlugIn implements PlugIn
{

	@Override
	public void run( final String arg )
	{
		final ImagePlus imp = WindowManager.getCurrentImage();
		if ( null == imp )
		{
			IJ.error( "Please open an image.", "Please open an image before running the Dendrite Dynamics Tracker plugin." );
			return;
		}

		final int nSlices = imp.getNSlices();
		if ( nSlices > 1 )
		{
			IJ.error( "Only 2D images.", "Dendrite Dynamics Tracker plugin only runs for 2D over time images." );
			return;
		}

		final Model model = new Model();
		model.setPhysicalUnits( imp.getCalibration().getUnits(), imp.getCalibration().getTimeUnit() );

		/*
		 * Get end points.
		 */

		final AnalyzeSkeleton_ skelAnalyzer = new AnalyzeSkeleton_();

		final int pruneIndex = AnalyzeSkeleton_.SHORTEST_BRANCH;
		final boolean pruneEnds = false; // Don't prune branch ends.
		final boolean shortPath = false; // Don't compute shortest path.
		final boolean silent = true;
		final boolean verbose = false;
		final Roi roi = null; // Process all.
		imp.setRoi( ( Roi ) null );

		final int nFrames = imp.getNFrames();
		final double pixelWidth = imp.getCalibration().pixelWidth;
		final double pixelHeight = imp.getCalibration().pixelHeight;
		final double frameInterval = imp.getCalibration().frameInterval;

		final SpotCollection spots = model.getSpots();
		IJ.showStatus( "Detecting branch ends" );
		for ( int t = 0; t < nFrames; t++ )
		{

			final ImagePlus frame = new Duplicator().run( imp, 1, 1, 1, 1, t + 1, t + 1 );
			skelAnalyzer.setup( "", frame );
			final SkeletonResult result = skelAnalyzer.run( pruneIndex, pruneEnds, shortPath, frame, silent, verbose, roi );

			final List< Point > endPoints = result.getListOfEndPoints();
			int id = 1;
			for ( final Point point : endPoints )
			{
				final double x = point.x * pixelWidth;
				final double y = point.y * pixelHeight;
				final double z = 0.;
				final double radius = 0.5;
				final double quality = 1.;
				final String name = String.format( "End_%3d", id++ );
				final Spot spot = new Spot( x, y, z, radius, quality, name );
				spot.putFeature( Spot.POSITION_T, Double.valueOf( t * frameInterval ) );
				spots.add( spot, Integer.valueOf( t ) );
			}

			IJ.showProgress( t + 1, nFrames );
		}
		spots.setVisible( true );

		/*
		 * Track them.
		 */

		IJ.showStatus( "Tracking branch ends" );

		final Settings settings = new Settings();
		settings.setFrom( imp );
		settings.detectorFactory = new ManualDetectorFactory<>();
		settings.trackerFactory = new SimpleSparseLAPTrackerFactory();
		settings.trackerSettings = LAPUtils.getDefaultLAPSettingsMap();
		settings.addEdgeAnalyzer( new EdgeTargetAnalyzer() );
		settings.addTrackAnalyzer( new TrackIndexAnalyzer() );
		settings.addTrackAnalyzer( new TrackDurationAnalyzer() );
		// TODO Add a track max excursion feature.
		// TODO Add a track main direction feature. Fit (x, y) by a line.

		final double maxLinkingDistance = 5.;
		settings.trackerSettings.put( TrackerKeys.KEY_LINKING_MAX_DISTANCE, Double.valueOf( maxLinkingDistance ) );
		settings.trackerSettings.put( TrackerKeys.KEY_GAP_CLOSING_MAX_DISTANCE, Double.valueOf( maxLinkingDistance ) );

		final TrackMate trackmate = new TrackMate( model, settings );
		if ( !trackmate.checkInput() || !trackmate.execTracking() )
		{
			IJ.error( "Problem with tracking.", trackmate.getErrorMessage() );
			return;
		}
		trackmate.computeSpotFeatures( false );
		trackmate.computeEdgeFeatures( false );
		trackmate.computeTrackFeatures( false );

		/*
		 * Visualize results.
		 */

		final HyperStackDisplayer displayer = new HyperStackDisplayer( model, new SelectionModel( model ), imp );
		final TrackColorGenerator trackColorGenerator = new PerTrackFeatureColorGenerator( model, TrackIndexAnalyzer.TRACK_INDEX );
		displayer.setDisplaySettings( TrackMateModelView.KEY_TRACK_COLORING, trackColorGenerator );
		displayer.render();

		final TrackMateGUIController controller = new TrackMateGUIController( trackmate );
		controller.setGUIStateString( ConfigureViewsDescriptor.KEY );
		controller.getGuimodel().addView( displayer );
		GuiUtils.positionWindow( controller.getGUI(), imp.getWindow() );

	}
}
