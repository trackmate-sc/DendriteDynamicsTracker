package fr.pasteur.iah.dendritedynamicstracker;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import javax.swing.BoxLayout;
import javax.swing.JFrame;
import javax.swing.JPanel;

import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartPanel;
import org.jfree.chart.JFreeChart;
import org.jfree.data.xy.DefaultXYDataset;
import org.jgrapht.graph.DefaultWeightedEdge;
import org.scijava.ItemIO;
import org.scijava.app.StatusService;
import org.scijava.command.Command;
import org.scijava.command.ContextCommand;
import org.scijava.log.LogService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

import fiji.plugin.trackmate.FeatureModel;
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
import fiji.plugin.trackmate.features.track.TrackSpotQualityFeatureAnalyzer;
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
import net.imglib2.util.Util;
import sc.fiji.analyzeSkeleton.Edge;
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

		analyzeDendriteDynamics( endPointTrackmate.getModel(), junctionModel, detectionResults );

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

	private static void analyzeDendriteDynamics( final Model endPointModel, final Model junctionModel, final DetectionResults detectionResults )
	{
		final TrackModel trackModel = endPointModel.getTrackModel();
		final FeatureModel featureModel = endPointModel.getFeatureModel();
		final Set< Integer > trackIDs = trackModel.trackIDs( true );
		int n = 0; // DEBUG
		for ( final Integer trackID : trackIDs )
		{
			/*
			 * Differentiate between junction tracks and end-point tracks based
			 * on their quality, in case the user merged both track types.
			 */
			final Double meanQuality = featureModel.getTrackFeature( trackID, TrackSpotQualityFeatureAnalyzer.TRACK_MEAN_QUALITY );
			if ( !meanQuality.equals( SkeletonKeyPointsDetector.END_POINTS_QUALITY_VALUE ) )
				continue;

			analyzeTrack( trackID, endPointModel, junctionModel, detectionResults );
			if ( n++ > 0 )
				break; // DEBUG
		}

	}

	private static void analyzeTrack( final Integer trackID, final Model endPointModel, final Model junctionModel, final DetectionResults detectionResults )
	{
		System.out.println(); // DEBUG
		final TrackModel trackModel = endPointModel.getTrackModel();
		final List< Spot > spots = new ArrayList<>( trackModel.trackSpots( trackID ) );
		spots.sort( Spot.frameComparator );

		/*
		 * Harvest raw branch length and junction ID.
		 */

		final double[] branchLength = new double[ spots.size() ];
		final double[] junctionIDs = new double[ spots.size() ];
		final double[] time = new double[ spots.size() ];

		for ( int i = 0; i < spots.size(); i++ )
		{
			final Spot spot = spots.get( i );
			time[ i ] = spot.getFeature( Spot.POSITION_T );

			final Spot junction = detectionResults.junctionMap.get( spot );
			final Integer junctionTrackID = junctionModel.getTrackModel().trackIDOf( junction );
			junctionIDs[ i ] = ( junctionTrackID != null )
					? junctionTrackID.doubleValue()
					: Double.NaN;

			final Vertex vertex = detectionResults.getVertexFor( spot );
			final Edge predecessor = vertex.getBranches().get( 0 );
			if ( null == predecessor )
				continue;

			final double length = predecessor.getLength_ra();
			branchLength[ i ] = length;
		}

		/*
		 * How many unique Junction IDs do we have? If we have more than 1, it
		 * means that the end-point "jumped" (incorrect tracking) or that the
		 * branch forked.
		 */

		final double[] uniqueIDs = Arrays.stream( junctionIDs )
				.filter( v -> !Double.isNaN( v ) )
				.distinct()
				.toArray();

		System.out.println( "Unique junction IDs found: " + Util.printCoordinates( uniqueIDs ) ); // DEBUG

		if ( uniqueIDs.length > 1 )
		{

			Arrays.sort( uniqueIDs );
			// Histogram of these unique IDs.
			final int[] counts = new int[ uniqueIDs.length ];
			for ( int i = 0; i < junctionIDs.length; i++ )
			{
				final double junctionID = junctionIDs[ i ];
				if ( Double.isNaN( junctionID ) )
					continue;
				final int idx = Arrays.binarySearch( uniqueIDs, junctionID );
				counts[ idx ]++;
			}

			/*
			 * Only correct if we can find a junction ID that is present for
			 * more than 1/4th of the total track duration.
			 */
			final double minCount = spots.size() / 4;
			final double[] uniqueIDsLong = IntStream.range( 0, counts.length )
					.filter( i -> counts[ i ] > minCount )
					.mapToDouble( i -> uniqueIDs[ i ] )
					.toArray();

			System.out.println( "Unique LONG junction IDs found: " + Util.printCoordinates( uniqueIDsLong ) ); // DEBUG

			/*
			 * Try to bridge and restore branch length for end-points that do
			 * not connect to the desired junction. We try each junction unique
			 * long id, and only take the one that gives the best correction.
			 */

			// Store the corrected branch length for each candidate.
			final double[][] correctedBranchLength = new double[ uniqueIDsLong.length ][ branchLength.length ];
			for ( int i = 0; i < uniqueIDsLong.length; i++ )
				correctedBranchLength[ i ] = Arrays.copyOf( branchLength, branchLength.length );

			// Store the corrected junction ID for each candidate.
			final double[][] correctedJunctionIDs = new double[ uniqueIDsLong.length ][ junctionIDs.length ];
			for ( int i = 0; i < uniqueIDsLong.length; i++ )
				correctedJunctionIDs[ i ] = Arrays.copyOf( junctionIDs, junctionIDs.length );

			// Stores the successful correction we have made for each candidate.
			final int[] successfulCorrections = new int[ uniqueIDsLong.length ];

			for ( int i = 0; i < uniqueIDsLong.length; i++ )
			{
				final double candidate = uniqueIDsLong[ i ];
				for ( int t = 0; t < junctionIDs.length; t++ )
				{
					if ( junctionIDs[ t ] == candidate )
						continue; // Do not touch.

					/*
					 * Breadth-first iterator to find the candidate junction.
					 * The queue stores the paths so that we can backtrack to
					 * the start of the search.
					 */

					final ArrayDeque< List< Vertex > > queue = new ArrayDeque<>();
					final Set< Vertex > visited = new HashSet<>();

					final Spot endPointSpot = spots.get( t );
					final Vertex endPointVertex = detectionResults.getVertexFor( endPointSpot );
					queue.add( Collections.singletonList( endPointVertex ) );

					System.out.println(); // DEBUG
					System.out.println( "At time " + t + ", searching for id=" + candidate + " from " + endPointVertex ); // DEBUG

					while ( !queue.isEmpty() )
					{
						final List< Vertex > path = queue.remove();

						// Take the last item of the path.
						final Vertex vertex = path.get( path.size() - 1 );
						if ( visited.contains( vertex ) )
							continue;

						System.out.println( " - visiting " + vertex.getPoints().get( 0 ) ); // DEBUG
						visited.add( vertex );

						final Spot junctionCandidate = detectionResults.getSpotFor( vertex );
						if ( null != junctionCandidate )
						{
							final Integer junctionCandidateID = junctionModel.getTrackModel().trackIDOf( junctionCandidate );
							if ( null != junctionCandidateID )
							{
								if ( junctionCandidateID.doubleValue() == candidate )
								{
									System.out.println( "   Found the right junction ID! Accepting this junction." ); // DEBUG
									System.out.println( "   Path to start: " ); // DEBUG

									// Compute new branch length.
									double sumBranchLength = 0.;
									Vertex source = path.get( 0 );
									for ( int j = 1; j < path.size(); j++ )
									{
										final Vertex target = path.get( j );
										for ( final Edge edge : target.getBranches() )
										{
											if ( source.equals( edge.getOppositeVertex( target ) ) )
											{
												sumBranchLength += edge.getLength_ra();
												break;
											}
										}

										source = target;
									}

									// Store new branch length and new junction id.
									correctedBranchLength[ i ][ t ] = sumBranchLength;
									correctedJunctionIDs[ i ][ t ] = candidate;
									successfulCorrections[ i ]++;

									break;
								}
								else
								{
									System.out.println( "   Found the junction ID " + junctionCandidateID + ". Continuing search." ); // DEBUG
								}
							}
							else
							{
								System.out.println( "   Found junction spot for this one, but it is not in a junction track." ); // DEBUG
								// What to do then?
							}

						}
						else
						{
							System.out.println( "   Could not find a junction spot for this one." ); // DEBUG
							// What to do then?
						}

						final ArrayList< Edge > branches = vertex.getBranches();
						for ( final Edge edge : branches )
						{
							final Vertex other = edge.getOppositeVertex( vertex );
							final List< Vertex > newPath = new ArrayList<>( path );
							newPath.add( other );
							queue.add( newPath );
						}
					}
				}

				final DefaultXYDataset datasetBranchLength = new DefaultXYDataset();
				datasetBranchLength.addSeries( "Branch length " + trackID, new double[][] { time, correctedBranchLength[ i ] } );
				final JFreeChart chartBranchLength = ChartFactory.createXYLineChart(
						trackModel.name( trackID ),
						"Time (" + endPointModel.getTimeUnits() + ")",
						"Branch length (" + endPointModel.getSpaceUnits() + ")",
						datasetBranchLength );
				final ChartPanel chartBranchLengthPanel = new ChartPanel( chartBranchLength );

				final DefaultXYDataset datasetJunctionID = new DefaultXYDataset();
				datasetJunctionID.addSeries( "Junction ID " + trackID, new double[][] { time, correctedJunctionIDs[ i ] } );
				final JFreeChart chartJunctionID = ChartFactory.createXYLineChart(
						trackModel.name( trackID ),
						"Time (" + endPointModel.getTimeUnits() + ")",
						"Junction ID",
						datasetJunctionID );
				final ChartPanel chartJunctionIDPanel = new ChartPanel( chartJunctionID );

				final JFrame frame = new JFrame( "Corrected branch length for " + trackModel.name( trackID ) + " with candidate id " + candidate );
				final JPanel panel = new JPanel();
				final BoxLayout layout = new BoxLayout( panel, BoxLayout.PAGE_AXIS );
				panel.setLayout( layout );
				panel.add( chartBranchLengthPanel );
				panel.add( chartJunctionIDPanel );

				frame.getContentPane().add( panel );
				frame.pack();
				frame.setVisible( true );
			}
		}

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

		}
		finally
		{
			model.endUpdate();
			logger.setProgress( 0 );
			logger.log( "Imported " + nNewTracks + " tracks made of " + nNewSpots + " spots.\n" );
		}
	}
}
