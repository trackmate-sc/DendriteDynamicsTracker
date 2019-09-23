package fr.pasteur.iah.dendritedynamicstracker;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
import fiji.plugin.trackmate.Settings;
import fiji.plugin.trackmate.Spot;
import fiji.plugin.trackmate.SpotCollection;
import fiji.plugin.trackmate.TrackMate;
import fiji.plugin.trackmate.TrackModel;
import fiji.plugin.trackmate.detection.ManualDetectorFactory;
import fiji.plugin.trackmate.features.edges.EdgeTargetAnalyzer;
import fiji.plugin.trackmate.features.track.TrackDurationAnalyzer;
import fiji.plugin.trackmate.features.track.TrackIndexAnalyzer;
import fiji.plugin.trackmate.gui.GuiUtils;
import fiji.plugin.trackmate.gui.TrackMateGUIController;
import fiji.plugin.trackmate.gui.descriptors.ConfigureViewsDescriptor;
import fiji.plugin.trackmate.providers.EdgeAnalyzerProvider;
import fiji.plugin.trackmate.providers.SpotAnalyzerProvider;
import fiji.plugin.trackmate.providers.TrackAnalyzerProvider;
import fiji.plugin.trackmate.tracking.LAPUtils;
import fiji.plugin.trackmate.tracking.TrackerKeys;
import fiji.plugin.trackmate.tracking.sparselap.SimpleSparseLAPTrackerFactory;
import fiji.plugin.trackmate.visualization.hyperstack.HyperStackDisplayer;
import fr.pasteur.iah.dendritedynamicstracker.trackmate.feature.JunctionIDAnalyzerFactory;
import fr.pasteur.iah.dendritedynamicstracker.trackmate.tracking.SkeletonEndPointTrackerFactory;
import ij.IJ;
import ij.ImagePlus;
import ij.gui.Roi;
import ij.plugin.ChannelSplitter;
import ij.plugin.Duplicator;
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
	private LogService log;

	@Parameter
	private StatusService status;

	@Parameter( type = ItemIO.INPUT )
	private ImagePlus imp = null;

	@Parameter( type = ItemIO.INPUT, label = "In what channel is the skeleton?" )
	private int skeletonChannel = 2;

	@Parameter( type = ItemIO.INPUT, label = "In what channel is raw data?" )
	private int dataChannel = 1;

	@Parameter( type = ItemIO.INPUT, label = "Max linking distance for junctions." )
	private double junctionMaxLinkingDistance = 5.;

	@Parameter( type = ItemIO.INPUT, label = "Max linking distance for end-points." )
	private double endPointMaxLinkingDistance = 5.;

	@Parameter( label = "Cycle-prunning method.", choices = {
			"No prunning",
			"Shortest branch",
			"Lowest intensity pixel",
			"Lowest intensity branch"
	} )
	private String cyclePrunningMethodStr = PRUNNING_METHOD_STRINGS[ 3 ];

	@Override
	public void run()
	{
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
		final int firstZ = 1;
		final int lastZ = skeleton.getNSlices();

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
			final ImagePlus skeletonFrame = duplicator.run( skeleton, 1, 1, firstZ, lastZ, frame + 1, frame + 1 );
			final ImagePlus origImpFrame = duplicator.run( origImp, 1, 1, firstZ, lastZ, frame + 1, frame + 1 );

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

		status.showStatus( "Tracking junctions." );

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

		/*
		 * Assign to each end-point the track ID of the junction they match.
		 */

		status.showStatus( "Passing junction IDs to end-points." );

		final TrackModel junctionTrackModel = junctionModel.getTrackModel();
		for ( final Spot endPoint : endPointSpots.iterable( true ) )
		{
			final Spot junction = junctionMap.get( endPoint );
			final Integer junctionTrackID = junctionTrackModel.trackIDOf( junction );
			if ( null == junctionTrackID )
				continue;

			endPoint.putFeature( JunctionIDAnalyzerFactory.FEATURE, Double.valueOf( junctionTrackID.doubleValue() ) );
			endPoint.setName( "->" + junctionTrackID );
		}

		/*
		 * Track end-points.
		 */

		status.showStatus( "Tracking end-points." );

		final Model endPointModel = new Model();
		endPointModel.setPhysicalUnits( imp.getCalibration().getUnits(), imp.getCalibration().getTimeUnit() );
		endPointModel.setSpots( endPointSpots, false );

		final Settings endPointSettings = new Settings();
		endPointSettings.setFrom( imp );
		endPointSettings.detectorFactory = new ManualDetectorFactory<>();
		endPointSettings.trackerFactory = new SkeletonEndPointTrackerFactory();

		endPointSettings.clearSpotAnalyzerFactories();
		final SpotAnalyzerProvider spotAnalyzerProvider = new SpotAnalyzerProvider();
		final List< String > spotAnalyzerKeys = spotAnalyzerProvider.getKeys();
		for ( final String key : spotAnalyzerKeys )
			endPointSettings.addSpotAnalyzerFactory( spotAnalyzerProvider.getFactory( key ) );

		endPointSettings.clearEdgeAnalyzers();
		final EdgeAnalyzerProvider edgeAnalyzerProvider = new EdgeAnalyzerProvider();
		final List< String > edgeAnalyzerKeys = edgeAnalyzerProvider.getKeys();
		for ( final String key : edgeAnalyzerKeys )
			endPointSettings.addEdgeAnalyzer( edgeAnalyzerProvider.getFactory( key ) );

		endPointSettings.clearTrackAnalyzers();
		final TrackAnalyzerProvider trackAnalyzerProvider = new TrackAnalyzerProvider();
		final List< String > trackAnalyzerKeys = trackAnalyzerProvider.getKeys();
		for ( final String key : trackAnalyzerKeys )
			endPointSettings.addTrackAnalyzer( trackAnalyzerProvider.getFactory( key ) );

		endPointSettings.addSpotAnalyzerFactory( new JunctionIDAnalyzerFactory<>() );
		endPointSettings.trackerSettings = new HashMap<>();
		endPointSettings.trackerSettings.put( TrackerKeys.KEY_LINKING_MAX_DISTANCE, Double.valueOf( endPointMaxLinkingDistance ) );
		endPointSettings.trackerSettings.put( TrackerKeys.KEY_ALTERNATIVE_LINKING_COST_FACTOR, Double.valueOf( TrackerKeys.DEFAULT_ALTERNATIVE_LINKING_COST_FACTOR ) );

		final TrackMate endPointTrackmate = new TrackMate( endPointModel, endPointSettings );
		if ( !endPointTrackmate.checkInput() || !endPointTrackmate.process() )
		{
			IJ.error( "Problem with tracking.", endPointTrackmate.getErrorMessage() );
			return;
		}

		/*
		 * Merge with junction results.
		 */

//		merge( endPointModel, junctionModel );
		endPointTrackmate.computeSpotFeatures( false );
		endPointTrackmate.computeEdgeFeatures( false );
		endPointTrackmate.computeTrackFeatures( false );

		/*
		 * Display results.
		 */

		final TrackMateGUIController controller2 = new TrackMateGUIController( endPointTrackmate );
		controller2.setGUIStateString( ConfigureViewsDescriptor.KEY );
		GuiUtils.positionWindow( controller2.getGUI(), imp.getWindow() );

		final HyperStackDisplayer displayer2 = new HyperStackDisplayer( endPointModel, controller2.getSelectionModel(), imp );
		controller2.getGuimodel().addView( displayer2 );
		displayer2.render();
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

	private static void merge( final Model model, final Model modelToMerge )
	{
		final int nNewTracks = modelToMerge.getTrackModel().nTracks( true );
		final Logger logger = model.getLogger();

		int progress = 0;
		model.beginUpdate();

		int nNewSpots = 0;
		try
		{
			for ( final int id : modelToMerge.getTrackModel().trackIDs( true ) )
			{

				/*
				 * Add new spots built on the ones in the source.
				 */

				final Set< Spot > spots = modelToMerge.getTrackModel().trackSpots( id );
				final HashMap< Spot, Spot > mapOldToNew = new HashMap<>( spots.size() );

				Spot newSpot = null; // we keep a reference to the new spot,
										// needed below
				for ( final Spot oldSpot : spots )
				{
					// An awkward way to avoid spot ID conflicts after loading
					// two files
					newSpot = new Spot( oldSpot );
					for ( final String feature : oldSpot.getFeatures().keySet() )
						newSpot.putFeature( feature, oldSpot.getFeature( feature ) );

					mapOldToNew.put( oldSpot, newSpot );
					model.addSpotTo( newSpot, oldSpot.getFeature( Spot.FRAME ).intValue() );
					nNewSpots++;
				}

				/*
				 * Link new spots from info in the file.
				 */

				final Set< DefaultWeightedEdge > edges = modelToMerge.getTrackModel().trackEdges( id );
				for ( final DefaultWeightedEdge edge : edges )
				{
					final Spot oldSource = modelToMerge.getTrackModel().getEdgeSource( edge );
					final Spot oldTarget = modelToMerge.getTrackModel().getEdgeTarget( edge );
					final Spot newSource = mapOldToNew.get( oldSource );
					final Spot newTarget = mapOldToNew.get( oldTarget );
					final double weight = modelToMerge.getTrackModel().getEdgeWeight( edge );

					model.addEdge( newSource, newTarget, weight );
				}

				/*
				 * Put back track names
				 */

				final String trackName = modelToMerge.getTrackModel().name( id );
				final int newId = model.getTrackModel().trackIDOf( newSpot );
				model.getTrackModel().setName( newId, trackName );

				progress++;
				logger.setProgress( ( double ) progress / nNewTracks );
			}

		}
		finally
		{
			model.endUpdate();
			logger.setProgress( 0 );
			logger.log( "Imported " + nNewTracks + " tracks made of " + nNewSpots + " spots.\n" );
		}
	}
}
