package fr.pasteur.iah.dendritedynamicstracker;

import java.util.HashMap;
import java.util.List;
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
import fr.pasteur.iah.dendritedynamicstracker.SkeletonKeyPointsDetector.DetectionResults;
import fr.pasteur.iah.dendritedynamicstracker.trackmate.feature.JunctionIDAnalyzerFactory;
import fr.pasteur.iah.dendritedynamicstracker.trackmate.tracking.SkeletonEndPointTrackerFactory;
import ij.IJ;
import ij.ImagePlus;
import net.imagej.ops.OpService;
import net.imagej.ops.special.function.Functions;

@Plugin( type = Command.class, name = "Dendrite Dynamics Tracker", menuPath = "Plugins>Tracking>Dendrite Dynamics Tracker" )
public class DendriteDynamicsTrackerCommand extends ContextCommand
{

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

	@Parameter( type = ItemIO.INPUT )
	private ImagePlus imp = null;

	@Parameter( type = ItemIO.INPUT, label = "In what channel is the skeleton?" )
	private int skeletonChannel = 2;

	@Parameter( type = ItemIO.INPUT, label = "In what channel is raw data?" )
	private int dataChannel = 1;

	@Parameter( type = ItemIO.INPUT, label = "Max linking distance for junctions." )
	private double junctionMaxLinkingDistance = 5.;

	@Parameter( label = "Cycle-prunning method.", choices = {
			"No prunning",
			"Shortest branch",
			"Lowest intensity pixel",
			"Lowest intensity branch"
	} )
	private String cyclePrunningMethodStr = PRUNNING_METHOD_STRINGS[ 3 ];

	@Parameter( type = ItemIO.INPUT, label = "Max linking distance for end-points." )
	private double endPointMaxLinkingDistance = 5.;

	@Parameter( type = ItemIO.INPUT, label = "Matched cost-factor for end-points." )
	private double matchedCostFactor = SkeletonEndPointTrackerFactory.DEFAULT_MATCHED_COST_FACTOR.doubleValue();

	@Parameter( type = ItemIO.INPUT, label = "Merge junction tracks with end-results?" )
	private boolean mergeJunctionTracks = false;

	@Override
	public void run()
	{

		/*
		 * Detect junctions and end-points.
		 */

		final int prunningMethod = getPrunningMethod( cyclePrunningMethodStr );
		final SkeletonKeyPointsDetector skeletonKeyPointOp = ( SkeletonKeyPointsDetector ) Functions.unary(
				ops, SkeletonKeyPointsDetector.class, DetectionResults.class, ImagePlus.class,
				skeletonChannel, dataChannel, prunningMethod );

		final DetectionResults detectionResults = skeletonKeyPointOp.calculate( imp );
		if ( null == detectionResults )
			return;

		/*
		 * Track junctions.
		 */

		status.showStatus( "Tracking junctions." );
		final Model junctionModel = trackJunctions(
				detectionResults,
				imp,
				junctionMaxLinkingDistance );
		if ( null == junctionModel )
			return;

		/*
		 * Track end-points.
		 */

		status.showStatus( "Tracking end-points." );
		final TrackMate endPointTrackmate = trackEndPoints(
				detectionResults,
				junctionModel,
				imp,
				endPointMaxLinkingDistance,
				matchedCostFactor,
				mergeJunctionTracks );
		if ( null == endPointTrackmate )
			return;

		/*
		 * Analyze results.
		 */

		status.showStatus( "Analyzing dendrite tracks." );
		final DendriteTrackAnalysis dendriteTrackAnalysis = new DendriteTrackAnalysis( endPointTrackmate.getModel(), junctionModel, detectionResults );
		if (!dendriteTrackAnalysis.checkInput() || !dendriteTrackAnalysis.process())
		{
			log.error( "Error while performing dendrite track analysis: " + dendriteTrackAnalysis.getErrorMessage() );
			return;
		}

		/*
		 * Display results.
		 */

		final TrackMateGUIController controller2 = new TrackMateGUIController( endPointTrackmate );
		controller2.setGUIStateString( ConfigureViewsDescriptor.KEY );
		GuiUtils.positionWindow( controller2.getGUI(), imp.getWindow() );

		final HyperStackDisplayer displayer2 = new HyperStackDisplayer( endPointTrackmate.getModel(), controller2.getSelectionModel(), imp );
		controller2.getGuimodel().addView( displayer2 );
		displayer2.render();
	}

	public static TrackMate trackEndPoints(
			final DetectionResults detectionResults,
			final Model junctionModel,
			final ImagePlus imp,
			final double endPointMaxLinkingDistance,
			final double matchedCostFactor,
			final boolean mergeJunctionTracks )
	{

		final Model endPointModel = new Model();
		endPointModel.setPhysicalUnits( imp.getCalibration().getUnits(), imp.getCalibration().getTimeUnit() );
		endPointModel.setSpots( detectionResults.endPointSpots, false );

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
		endPointSettings.trackerSettings.put( SkeletonEndPointTrackerFactory.KEY_MATCHED_COST_FACTOR, Double.valueOf( matchedCostFactor ) );

		final TrackMate endPointTrackmate = new TrackMate( endPointModel, endPointSettings );
		if ( !endPointTrackmate.checkInput() || !endPointTrackmate.process() )
		{
			IJ.error( "Problem with tracking.", endPointTrackmate.getErrorMessage() );
			return null;
		}

		/*
		 * Merge with junction results.
		 */

		if ( mergeJunctionTracks )
			merge( endPointModel, junctionModel );

		endPointTrackmate.computeSpotFeatures( false );
		endPointTrackmate.computeEdgeFeatures( false );
		endPointTrackmate.computeTrackFeatures( false );

		return endPointTrackmate;
	}

	public static Model trackJunctions(
			final DetectionResults detectionResults,
			final ImagePlus imp,
			final double junctionMaxLinkingDistance )
	{

		final Model junctionModel = new Model();
		junctionModel.setPhysicalUnits( imp.getCalibration().getUnits(), imp.getCalibration().getTimeUnit() );
		junctionModel.setSpots( detectionResults.junctionsSpots, false );

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
			return null;
		}
		junctionTrackmate.computeSpotFeatures( false );
		junctionTrackmate.computeEdgeFeatures( false );
		junctionTrackmate.computeTrackFeatures( false );

		/*
		 * Assign to each end-point the track ID of the junction they match.
		 */

		final TrackModel junctionTrackModel = junctionModel.getTrackModel();
		for ( final Spot endPoint : detectionResults.endPointSpots.iterable( true ) )
		{
			final Spot junction = detectionResults.junctionMap.get( endPoint );
			final Integer junctionTrackID = junctionTrackModel.trackIDOf( junction );
			if ( null == junctionTrackID )
			{
				endPoint.setName( "no junction" );
				continue;
			}

			endPoint.putFeature( JunctionIDAnalyzerFactory.FEATURE, Double.valueOf( junctionTrackID.doubleValue() ) );
			endPoint.setName( "->" + junctionTrackID );
		}

		return junctionModel;
	}

	private static final int getPrunningMethod( final String cyclePrunningMethodStr )
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
			/*
			 *  Add spots that are part of tracks.
			 */

			// To harvest the max Id.
			int maxID = -1;
			for ( final int id : modelToMerge.getTrackModel().trackIDs( true ) )
			{

				if (id > maxID)
					maxID = id;

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
					newSpot.setName( "J" + id );
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

			/*
			 * Add lonely spots.
			 */

			maxID++;
			for ( final Spot oldSpot : modelToMerge.getSpots().iterable( true ) )
			{
				if ( modelToMerge.getTrackModel().trackIDOf( oldSpot ) != null )
					continue;

				// An awkward way to avoid spot ID conflicts after loading
				// two files
				final Spot newSpot = new Spot( oldSpot );
				for ( final String feature : oldSpot.getFeatures().keySet() )
					newSpot.putFeature( feature, oldSpot.getFeature( feature ) );

				newSpot.setName( "JL" + maxID++ );
				model.addSpotTo( newSpot, oldSpot.getFeature( Spot.FRAME ).intValue() );
				nNewSpots++;
			}

		}
		finally
		{
			model.endUpdate();
			logger.setProgress( 0 );
			logger.log( "Imported " + nNewTracks + " tracks and " + nNewSpots + " spots.\n" );
		}
	}
}
