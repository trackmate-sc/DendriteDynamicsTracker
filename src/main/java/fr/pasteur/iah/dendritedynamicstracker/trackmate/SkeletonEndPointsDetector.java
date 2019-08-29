package fr.pasteur.iah.dendritedynamicstracker.trackmate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import fiji.plugin.trackmate.Spot;
import fiji.plugin.trackmate.detection.DetectionUtils;
import fiji.plugin.trackmate.detection.SpotDetector;
import fr.pasteur.iah.dendritedynamicstracker.trackmate.feature.BranchLengthAnalyzerFactory;
import fr.pasteur.iah.dendritedynamicstracker.trackmate.feature.JunctionIDAnalyzerFactory;
import ij.ImagePlus;
import ij.plugin.Duplicator;
import net.imglib2.Interval;
import net.imglib2.RandomAccessible;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.view.IntervalView;
import net.imglib2.view.Views;
import sc.fiji.analyzeSkeleton.AnalyzeSkeleton_;
import sc.fiji.analyzeSkeleton.Edge;
import sc.fiji.analyzeSkeleton.Graph;
import sc.fiji.analyzeSkeleton.Point;
import sc.fiji.analyzeSkeleton.SkeletonResult;
import sc.fiji.analyzeSkeleton.Vertex;

public class SkeletonEndPointsDetector< T extends RealType< T > & NativeType< T > > implements SpotDetector< T >
{
	private final static String BASE_ERROR_MESSAGE = "SkeletonEndPointsDetector: ";

	final static double END_POINTS_QUALITY_VALUE = 1.;

	final static double JUNCTION_POINTS_QUALITY_VALUE = 2.;

	private final RandomAccessible< T > img;

	private final Interval interval;

	private final double[] calibration;

	/** The list of {@link Spot} that will be populated by this detector. */
	private final List< Spot > spots = new ArrayList<>();

	private String errorMessage;

	private long processingTime;

	public SkeletonEndPointsDetector( final RandomAccessible< T > img, final Interval interval, final double[] calibration )
	{
		this.img = img;
		this.interval = DetectionUtils.squeeze( interval );
		this.calibration = calibration;
	}

	@Override
	public List< Spot > getResult()
	{
		return spots;
	}

	@Override
	public boolean checkInput()
	{
		if ( null == img )
		{
			errorMessage = BASE_ERROR_MESSAGE + "Image is null.";
			return false;
		}
		if ( img.numDimensions() > 3 || img.numDimensions() < 2 )
		{
			errorMessage = BASE_ERROR_MESSAGE + "Image must be 2D or 3D, got " + img.numDimensions() + "D.";
			return false;
		}
		return true;
	}

	@Override
	public boolean process()
	{
		final long start = System.currentTimeMillis();
		spots.clear();

		final AnalyzeSkeleton_ skelAnalyzer = new AnalyzeSkeleton_();

		final int pruneIndex = AnalyzeSkeleton_.SHORTEST_BRANCH;
		final boolean pruneEnds = false; // Don't prune branch ends.
		final boolean shortPath = false; // Don't compute shortest path.
		final boolean silent = true;
		final boolean verbose = false;

		final IntervalView< T > intervalView = Views.interval( img, interval );
		final IntervalView< T > crop = Views.zeroMin( intervalView );
		final ImagePlus imp = ImageJFunctions.wrap( crop, "Input for AnalyseSkeleton" );
		/*
		 * Duplicate it on a non-virtual image, otherwise Analyze Skeleton does
		 * not work.
		 */
		final ImagePlus frame = new Duplicator().run( imp );

		skelAnalyzer.setup( "", frame );
		final SkeletonResult result = skelAnalyzer.run( pruneIndex, pruneEnds, shortPath, frame, silent, verbose, null );

		final long startx = interval.numDimensions() > 0 ? interval.min( 0 ) : 0;
		final long starty = interval.numDimensions() > 1 ? interval.min( 1 ) : 0;
		final long startz = interval.numDimensions() > 2 ? interval.min( 2 ) : 0;
		final long[] topLeft = new long[] { startx, starty, startz };

		final Graph[] graphs = result.getGraph();
		for ( final Graph graph : graphs )
		{
			final List< Vertex > vertices = graph.getVertices();
			final Map< Vertex, Spot > vertexMap = new HashMap<>();

			/*
			 * Find junctions.
			 */

			for ( final Vertex vertex : vertices )
			{
				if ( vertex.getBranches().size() == 1 )
					continue;

				final Spot spot = vertexToSpot( vertex, calibration, topLeft );
				vertexMap.put( vertex, spot );
				spots.add( spot );
			}

			/*
			 * Find end points and link them to their junction.
			 */

			for ( final Vertex vertex : vertices )
			{
				if ( vertex.getBranches().size() != 1 )
					continue;

				final Spot spot = vertexToSpot( vertex, calibration, topLeft );
				final Edge predecessor = vertex.getBranches().get( 0 );
				if ( null == predecessor )
					continue;

				final Vertex oppositeVertex = predecessor.getOppositeVertex( vertex );
				final Spot junctionSpot = vertexMap.get( oppositeVertex );
				if ( null != junctionSpot )
				{
					spot.setName( "->" + junctionSpot.ID() );
					spot.putFeature( JunctionIDAnalyzerFactory.FEATURE, Double.valueOf( junctionSpot.ID() ) );
					final double branchLength = predecessor.getLength_ra();
					spot.putFeature( BranchLengthAnalyzerFactory.FEATURE, Double.valueOf( branchLength ) );

				}
				else
				{
					spot.setName( "No junction" );
				}
				spots.add( spot );
			}

		}

		final long end = System.currentTimeMillis();
		this.processingTime = end - start;

		return true;
	}

	private static final Spot vertexToSpot( final Vertex vertex, final double[] calibration, final long[] start )
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

		final boolean isJunction = vertex.getBranches().size() > 1;

		final double x = ( start[ 0 ] + xi ) * calibration[ 0 ];
		final double y = ( start[ 1 ] + yi ) * calibration[ 1 ];
		final double z = ( start[ 2 ] + zi ) * calibration[ 2 ];
		final double radius = isJunction ? 1. : 0.5;
		final double quality = isJunction ? JUNCTION_POINTS_QUALITY_VALUE : END_POINTS_QUALITY_VALUE;
		final Spot spot = new Spot( x, y, z, radius, quality );
		return spot;
	}

	@Override
	public String getErrorMessage()
	{
		return errorMessage;
	}

	@Override
	public long getProcessingTime()
	{
		return processingTime;
	}
}
