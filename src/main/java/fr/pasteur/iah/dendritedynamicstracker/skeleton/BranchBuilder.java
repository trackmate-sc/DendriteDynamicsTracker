package fr.pasteur.iah.dendritedynamicstracker.skeleton;

import static fr.pasteur.iah.dendritedynamicstracker.skeleton.SkeletonAnalyzer.END_POINT_TAG;
import static fr.pasteur.iah.dendritedynamicstracker.skeleton.SkeletonAnalyzer.JUNCTION_TAG;
import static fr.pasteur.iah.dendritedynamicstracker.skeleton.SkeletonAnalyzer.SLAB_TAG;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.imglib2.Cursor;
import net.imglib2.Localizable;
import net.imglib2.Point;
import net.imglib2.RandomAccess;
import net.imglib2.algorithm.neighborhood.Neighborhood;
import net.imglib2.algorithm.neighborhood.RectangleShape;
import net.imglib2.algorithm.neighborhood.RectangleShape.NeighborhoodsAccessible;
import net.imglib2.img.Img;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.integer.UnsignedIntType;
import net.imglib2.util.Intervals;
import net.imglib2.util.Util;

import fr.pasteur.iah.dendritedynamicstracker.skeleton.SkeletonAnalysis.Branch;
import fr.pasteur.iah.dendritedynamicstracker.skeleton.SkeletonAnalysis.Junction;

public class BranchBuilder
{

	private static final int NOTHING_FOUND = 0;

	private final Img< UnsignedByteType > taggedSkeleton;

	/**
	 * Copy of the source image, will be modified during iteration.
	 */
	private Img< UnsignedByteType > img;

	/**
	 * Random access into the square neighborhood of the image.
	 */
	private RandomAccess< Neighborhood< UnsignedByteType > > raNeighborhood;

	private final Img< UnsignedIntType > junctionImg;

	private final Map< Integer, Junction > junctionIdMap;

	private final RandomAccess< UnsignedIntType > raJunctionImg;

	public BranchBuilder( final Img< UnsignedByteType > taggedSkeleton, final Collection< Junction > junctions )
	{
		this.taggedSkeleton = taggedSkeleton;

		/*
		 * Create a junction image, where the pixel value is given by the
		 * junction ID.
		 */

		this.junctionImg = Util.getArrayOrCellImgFactory( taggedSkeleton, new UnsignedIntType() ).create( taggedSkeleton );
		this.raJunctionImg = junctionImg.randomAccess( junctionImg );
		this.junctionIdMap = new HashMap<>();
		final RandomAccess< UnsignedIntType > ra = junctionImg.randomAccess( junctionImg );
		for ( final Junction junction : junctions )
		{
			junctionIdMap.put( Integer.valueOf( junction.id() ), junction );
			final ByteValueIterator it = new ByteValueIterator( taggedSkeleton, junction.getPixel(), JUNCTION_TAG );
			while ( it.hasNext() )
			{
				ra.setPosition( it.next() );
				ra.get().setInt( junction.id() );
			}
		}
	}

	public List< Branch > process()
	{
		this.img = taggedSkeleton.copy();
		final NeighborhoodsAccessible< UnsignedByteType > neighborhoods = new RectangleShape( 1, true ).neighborhoodsRandomAccessible( img );
		this.raNeighborhood = neighborhoods.randomAccess( Intervals.expand( img, 1 ) );

		final List< Branch > branches = new ArrayList<>();

		final Cursor< UnsignedByteType > cursor = img.localizingCursor();
		while ( cursor.hasNext() )
		{
			cursor.fwd();
			if ( cursor.get().get() == END_POINT_TAG )
			{
				final Branch branch = processBranch( cursor );
				if ( null != branch )
					branches.add( branch );
			}

		}
		return branches;
	}

	private Branch processBranch( final Localizable start )
	{
		final Point current = Point.wrap( new long[ img.numDimensions() ] );
		current.setPosition( start );
		final RandomAccess< UnsignedByteType > ra = img.randomAccess( img );
		final List< Point > branchPixels = new ArrayList<>();
		branchPixels.add( new Point( start ) );

		while ( true )
		{
			// Mark as iterated.
			ra.setPosition( current );
			ra.get().set( 0 );

			// Move to next pixel.
			final int val = nextSlabPixel( current );

			switch ( val )
			{
			case SLAB_TAG:
				/*
				 * Found a slab pixel, I am still walking along the branch.
				 */
				branchPixels.add( new Point( current ) );
				break;

			case END_POINT_TAG:
				/*
				 * I reached another end-point. It means that this tree is made
				 * of a single branch with only two end-points.
				 */
				return createSingleBranch( start, current, branchPixels );

			case JUNCTION_TAG:
				/*
				 * I reached a junction pixel. This is the junction from which
				 * this branch protrudes. Find what junction it is and finish.
				 */
				return createBranch( start, current, branchPixels );

			default:
				/*
				 * I am in trouble because this situation is unexpected. We
				 * reached the border of the image or something like this. We
				 * give up on this branch.
				 */
				return null;
			// throw new IllegalStateException( "Did not found a successor pixel
			// for the skeleton at " + current );
			}
		}
	}

	private Branch createBranch( final Localizable start, final Point junctionPixel, final List< Point > branchPixels )
	{
		raJunctionImg.setPosition( junctionPixel );
		final int junctionID = raJunctionImg.get().getInt();
		final Junction junction = junctionIdMap.get( Integer.valueOf( junctionID ) );
		final Branch branch = new Branch( start, junctionPixel, junction, calculateLength( branchPixels ) );
		if ( null != junction )
			junction.addBranch( branch );
		return branch;
	}

	private Branch createSingleBranch( final Localizable start, final Point current, final List< Point > branchPixels )
	{
		return new Branch( start, current, null, calculateLength( branchPixels ) );
	}

	private int nextSlabPixel( final Point current )
	{
		raNeighborhood.setPosition( current );
		final Cursor< UnsignedByteType > cursor = raNeighborhood.get().localizingCursor();
		while ( cursor.hasNext() )
		{
			cursor.fwd();
			if ( !Intervals.contains( taggedSkeleton, cursor ) )
				continue;
			if ( cursor.get().get() != 0 )
			{
				current.setPosition( cursor );
				return cursor.get().get();
			}
		}
		// Nothing found.
		return NOTHING_FOUND;
	}

	private static double calculateLength( final List< Point > points )
	{
		if ( points.size() < 2 )
			return 0.;

		final int numDimensions = points.get( 0 ).numDimensions();
		double d2 = 0.;
		Point source = points.get( 0 );
		for ( int i = 1; i < points.size(); i++ )
		{
			final Point target = points.get( i );
			for ( int d = 0; d < numDimensions; d++ )
			{
				final double dx = target.getDoublePosition( d ) - source.getDoublePosition( d );
				d2 += dx * dx;
			}
			source = target;
		}
		return Math.sqrt( d2 );
	}
}
