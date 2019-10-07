package fr.pasteur.iah.dendritedynamicstracker;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.scijava.ItemIO;
import org.scijava.app.StatusService;
import org.scijava.log.LogService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

import fiji.plugin.trackmate.Spot;
import fiji.plugin.trackmate.SpotCollection;
import fr.pasteur.iah.dendritedynamicstracker.SkeletonKeyPointsDetector.DetectionResults;
import ij.ImagePlus;
import ij.gui.Roi;
import ij.plugin.ChannelSplitter;
import ij.plugin.Duplicator;
import net.imagej.ops.special.function.AbstractUnaryFunctionOp;
import sc.fiji.analyzeSkeleton.AnalyzeSkeleton_;
import sc.fiji.analyzeSkeleton.Edge;
import sc.fiji.analyzeSkeleton.Graph;
import sc.fiji.analyzeSkeleton.Point;
import sc.fiji.analyzeSkeleton.SkeletonResult;
import sc.fiji.analyzeSkeleton.Vertex;

@Plugin( type = SkeletonKeyPointsDetector.class )
public class SkeletonKeyPointsDetector extends AbstractUnaryFunctionOp< ImagePlus, DetectionResults >
{

	final static double END_POINTS_QUALITY_VALUE = 1.;

	final static double JUNCTION_POINTS_QUALITY_VALUE = 2.;

	final static double END_POINTS_RADIUS = 0.5;

	final static double JUNCTION_POINTS_RADIUS = 1.;

	@Parameter
	private LogService log;

	@Parameter
	private StatusService status;

	@Parameter( type = ItemIO.INPUT, label = "In what channel is the skeleton?" )
	private int skeletonChannel = 2;

	@Parameter( type = ItemIO.INPUT, label = "In what channel is raw data?" )
	private int dataChannel = 1;

	@Parameter( type = ItemIO.INPUT, label = "Prunning method" )
	private int prunningMethod = 3;

	@Override
	public DetectionResults calculate( final ImagePlus imp )
	{

		final ImagePlus[] channels = ChannelSplitter.split( imp );
		if ( channels.length < skeletonChannel )
		{
			log.error( "Skeleton channel defined as " + skeletonChannel + " but source image only has " + channels.length + " channels." );
			return null;
		}
		if ( channels.length < dataChannel )
		{
			log.error( "Raw data channel defined as " + dataChannel + " but source image only has " + channels.length + " channels." );
			return null;
		}

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

		return new DetectionResults( junctionsSpots, endPointSpots, junctionMap );

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

	public static final class DetectionResults
	{

		public final SpotCollection junctionsSpots;

		public final SpotCollection endPointSpots;

		/**
		 * Maps a skeleton end-point spot to its junction spot.
		 */
		public final Map< Spot, Spot > junctionMap;

		public DetectionResults( final SpotCollection junctionsSpots, final SpotCollection endPointSpots, final Map< Spot, Spot > junctionMap )
		{
			this.junctionsSpots = junctionsSpots;
			this.endPointSpots = endPointSpots;
			this.junctionMap = junctionMap;
		}
	}

}
