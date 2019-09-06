package fr.pasteur.iah.dendritedynamicstracker;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.scijava.ItemIO;
import org.scijava.app.StatusService;
import org.scijava.command.Command;
import org.scijava.command.ContextCommand;
import org.scijava.convert.ConvertService;
import org.scijava.log.LogService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

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
import fiji.plugin.trackmate.visualization.hyperstack.HyperStackDisplayer;
import ij.IJ;
import ij.ImagePlus;
import ij.gui.Roi;
import ij.plugin.ChannelSplitter;
import ij.plugin.Duplicator;
import net.imagej.Dataset;
import net.imagej.DatasetService;
import sc.fiji.analyzeSkeleton.AnalyzeSkeleton_;
import sc.fiji.analyzeSkeleton.Edge;
import sc.fiji.analyzeSkeleton.Graph;
import sc.fiji.analyzeSkeleton.Point;
import sc.fiji.analyzeSkeleton.SkeletonResult;
import sc.fiji.analyzeSkeleton.Vertex;

@Plugin( type = Command.class, name = "Dendrite Dynamics Tracker", menuPath = "Plugins>Tracking>Dendrite Dynamics Tracker" )
public class DendriteDynamicsTrackerCommand extends ContextCommand
{

	private static final String[] PRUNNING_METHOD_STRINGS = new String[] {
			"No prunning",
			"Shortest branch",
			"Lowest intensity pixel",
			"Lowest intensity branch"
	};

	final static double END_POINTS_QUALITY_VALUE = 1.;

	final static double JUNCTION_POINTS_QUALITY_VALUE = 2.;

	final static double END_POINTS_RADIUS = 0.5;

	final static double JUNCTION_POINTS_RADIUS = 1.;

	@Parameter
	private ConvertService convertService;

	@Parameter
	private DatasetService datasetService;

	@Parameter
	private LogService log;

	@Parameter
	private StatusService status;

	@Parameter( type = ItemIO.INPUT )
	private Dataset dataset = null;

	@Parameter( type = ItemIO.INPUT, label = "In what channel is the skeleton?" )
	private int skeletonChannel = 2;

	@Parameter( type = ItemIO.INPUT, label = "In what channel is raw data?" )
	private int dataChannel = 1;

	@Parameter( type = ItemIO.INPUT, label = "Max linking distance for junctions." )
	private double junctionMaxLinkingDistance = 5.;

	@Parameter( type = ItemIO.INPUT, label = "Max linking distance for end-points." )
	private double endPointMaxLinkingDistance = 15.;

	@Parameter( type = ItemIO.INPUT, label = "Max frame-gap for end-points." )
	private int endPointMaxFrameGap = 2;

	@Parameter( type = ItemIO.INPUT, label = "Cycle-prunning method." )
	private int cycePrunningMethod = 2;

	@Parameter( label = "Cycle-prunning method.", choices = {
			"No prunning",
			"Shortest branch",
			"Lowest intensity pixel",
			"Lowest intensity branch"
	} )
	private String cyclePrunningMethodStr = PRUNNING_METHOD_STRINGS[ 3 ];

	private ImagePlus imp = null;

	@Override
	public void run()
	{
		if ( imp == null )
			setDataset( dataset );

		final ImagePlus[] channels = ChannelSplitter.split( imp );
		if ( channels.length < skeletonChannel )
		{
			log.error( "Skeleton channel defined as " + skeletonChannel + " but source image only has " + channels.length + " channels." );
			return;
		}
		if ( channels.length < dataChannel )
		{
			log.error( "Raw data channel defined as " + dataChannel + " but source image only has " + channels.length + " channels." );
			return;
		}

		/*
		 * Detect junctions and end-points.
		 */

		final int prunningMethod = getPrunningMethod();

		final ImagePlus skeleton = channels[ skeletonChannel - 1 ];
		final ImagePlus origImp = channels[ dataChannel - 1 ];

		final Roi roi = imp.getRoi();
		skeleton.setRoi( roi );
		origImp.setRoi( roi );
		final int[] start = new int[] {
				null == roi ? 0 : roi.getBounds().x,
				null == roi ? 0 : roi.getBounds().y,
				0 };

		final double[] calibration = new double[] {
				imp.getCalibration().pixelWidth,
				imp.getCalibration().pixelHeight,
				imp.getCalibration().pixelDepth
		};
		final double frameInterval = imp.getCalibration().frameInterval;

		final int nFrames = skeleton.getNFrames();
		final int firstZ = 0;
		final int lastZ = skeleton.getNSlices() - 1;

		final Duplicator duplicator = new Duplicator();
		final AnalyzeSkeleton_ skelAnalyzer = new AnalyzeSkeleton_();

		final boolean pruneEnds = false; // Don't prune branch ends.
		final boolean shortPath = false; // Don't compute shortest path.
		final boolean silent = true;
		final boolean verbose = false;

		final SpotCollection junctionsSpots = new SpotCollection();
		final SpotCollection endPointSpots = new SpotCollection();
		/**
		 * Maps a graph vertex to the spot created from it.
		 */
		final Map< Vertex, Spot > vertexMap = new HashMap<>();
		/**
		 * Maps a Spot to its graph.
		 */
		final Map< Spot, Graph > graphMap = new HashMap<>();
		/**
		 * Maps a skeleton end-point to its junction spot.
		 */
		final Map< Spot, Spot > junctionMap = new HashMap<>();

		status.showStatus( "Processing skeleton." );
		for ( int frame = 0; frame < nFrames; frame++ )
		{
			final ImagePlus skeletonFrame = duplicator.run( skeleton, 0, 0, firstZ, lastZ, frame, frame );
			final ImagePlus origImpFrame = duplicator.run( origImp, 0, 0, firstZ, lastZ, frame, frame );

			skelAnalyzer.setup( "", skeletonFrame );
			final SkeletonResult result = skelAnalyzer.run( prunningMethod, pruneEnds, shortPath, origImpFrame, silent, verbose, null );

			final Graph[] graphs = result.getGraph();
			for ( final Graph graph : graphs )
			{
				final List< Vertex > vertices = graph.getVertices();

				/*
				 * Find junctions.
				 */

				for ( final Vertex vertex : vertices )
				{
					if ( vertex.getBranches().size() == 1 )
						continue;

					final Spot spot = vertexToSpot( vertex, calibration, start );
					spot.putFeature( Spot.POSITION_T, frame * frameInterval );
					vertexMap.put( vertex, spot );
					graphMap.put( spot, graph );

					junctionsSpots.add( spot, Integer.valueOf( frame ) );
				}

				/*
				 * Find end points and link them to their junction.
				 */

				for ( final Vertex vertex : vertices )
				{
					if ( vertex.getBranches().size() != 1 )
						continue;

					final Spot spot = vertexToSpot( vertex, calibration, start );
					spot.putFeature( Spot.POSITION_T, frame * frameInterval );
					vertexMap.put( vertex, spot );
					graphMap.put( spot, graph );

					endPointSpots.add( spot, Integer.valueOf( frame ) );

					// Find matching junction.
					final Edge predecessor = vertex.getBranches().get( 0 );
					if ( null == predecessor )
						continue;

					final Vertex oppositeVertex = predecessor.getOppositeVertex( vertex );
					final Spot junctionSpot = vertexMap.get( oppositeVertex );
					if ( null != junctionSpot )
						junctionMap.put( spot, junctionSpot );

				}

			}

			junctionsSpots.setVisible( true );
			endPointSpots.setVisible( true );

			status.showProgress( frame, nFrames );
		}

		/*
		 * Track junctions.
		 */

		final Model junctionModel = new Model();
		junctionModel.setPhysicalUnits( imp.getCalibration().getUnits(), imp.getCalibration().getTimeUnit() );
		junctionModel.setSpots( junctionsSpots, false );

		final Settings junctionSettings = new Settings();
		junctionSettings.setFrom( imp );
		junctionSettings.detectorFactory = new ManualDetectorFactory<>();
		junctionSettings.trackerFactory = new SimpleSparseLAPTrackerFactory();
		junctionSettings.trackerSettings = LAPUtils.getDefaultLAPSettingsMap();
		junctionSettings.addEdgeAnalyzer( new EdgeTargetAnalyzer() );
		junctionSettings.addTrackAnalyzer( new TrackIndexAnalyzer() );
		junctionSettings.addTrackAnalyzer( new TrackDurationAnalyzer() );
		junctionSettings.trackerSettings.put( TrackerKeys.KEY_LINKING_MAX_DISTANCE, Double.valueOf( junctionMaxLinkingDistance ) );
		junctionSettings.trackerSettings.put( TrackerKeys.KEY_ALLOW_GAP_CLOSING, Boolean.FALSE );

		final TrackMate junctionTrackmate = new TrackMate( junctionModel, junctionSettings );
		if ( !junctionTrackmate.execTracking() )
		{
			IJ.error( "Problem with tracking.", junctionTrackmate.getErrorMessage() );
			return;
		}
		junctionTrackmate.computeSpotFeatures( false );
		junctionTrackmate.computeEdgeFeatures( false );
		junctionTrackmate.computeTrackFeatures( false );

		final HyperStackDisplayer displayer = new HyperStackDisplayer( junctionModel, new SelectionModel( junctionModel ), imp );
		displayer.render();

		final TrackMateGUIController controller = new TrackMateGUIController( junctionTrackmate );
		controller.setGUIStateString( ConfigureViewsDescriptor.KEY );
		controller.getGuimodel().addView( displayer );
		GuiUtils.positionWindow( controller.getGUI(), imp.getWindow() );

		/*
		 * Track end-points.
		 */

		// TODO

	}

	private static final Spot vertexToSpot( final Vertex vertex, final double[] calibration, final int[] start )
	{
		final List< Point > points = vertex.getPoints();
		final double xi = points.stream()
				.mapToDouble( p -> p.x )
				.average()
				.getAsDouble();
		final double yi = points.stream()
				.mapToDouble( p -> p.y )
				.average()
				.getAsDouble();
		final double zi = points.stream()
				.mapToDouble( p -> p.z )
				.average()
				.getAsDouble();

		final double x = ( start[ 0 ] + xi ) * calibration[ 0 ];
		final double y = ( start[ 1 ] + yi ) * calibration[ 1 ];
		final double z = ( start[ 2 ] + zi ) * calibration[ 2 ];
		final boolean isJunction = vertex.getBranches().size() > 1;
		final double radius = isJunction ? JUNCTION_POINTS_RADIUS : END_POINTS_RADIUS;
		final double quality = isJunction ? JUNCTION_POINTS_QUALITY_VALUE : END_POINTS_QUALITY_VALUE;
		final Spot spot = new Spot( x, y, z, radius, quality );
		return spot;
	}

	private int getPrunningMethod()
	{
		for ( int i = 0; i < PRUNNING_METHOD_STRINGS.length; i++ )
			if ( PRUNNING_METHOD_STRINGS[ i ].equals( cyclePrunningMethodStr ) )
				return i;

		return 3;
	}

	public void setDataset( final Dataset dataset )
	{
		this.dataset = dataset;
		setImagePlus();
	}

	private void setImagePlus()
	{
		imp = convertService.convert( dataset, ImagePlus.class );
	}

}
