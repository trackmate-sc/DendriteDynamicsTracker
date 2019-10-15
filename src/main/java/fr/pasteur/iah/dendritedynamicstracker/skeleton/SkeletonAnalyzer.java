package fr.pasteur.iah.dendritedynamicstracker.skeleton;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import net.imagej.ops.special.function.AbstractBinaryFunctionOp;
import net.imglib2.Cursor;
import net.imglib2.Interval;
import net.imglib2.Point;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.algorithm.neighborhood.Neighborhood;
import net.imglib2.algorithm.neighborhood.RectangleShape;
import net.imglib2.algorithm.neighborhood.RectangleShape.NeighborhoodsAccessible;
import net.imglib2.img.Img;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.util.Intervals;
import net.imglib2.util.Util;
import net.imglib2.view.IntervalView;
import net.imglib2.view.Views;

import org.scijava.plugin.Plugin;

import fr.pasteur.iah.dendritedynamicstracker.skeleton.SkeletonAnalysis.Branch;
import fr.pasteur.iah.dendritedynamicstracker.skeleton.SkeletonAnalysis.Junction;

@Plugin( type = SkeletonAnalyzer.class )
public class SkeletonAnalyzer< T extends RealType< T > > extends AbstractBinaryFunctionOp< RandomAccessible< T >, Interval, SkeletonAnalysis >
{

	static final int END_POINT_TAG = 2;
	
	static final int JUNCTION_TAG = 3;
	
	static final int SLAB_TAG = 1;
	
	private static final int JUNCTION_TAG_TEMP = 255;

	@Override
	public SkeletonAnalysis calculate( final RandomAccessible< T > skeleton, final Interval interval )
	{

		/*
		 * Generate skeleton-tagged image.
		 */
		final Img< UnsignedByteType > taggedSkeleton = createTaggedSkeleton( skeleton, interval );

		/*
		 * Group junction pixels into junctions.
		 */
		final List< Junction > junctions = groupJunctions( taggedSkeleton );

		/*
		 * Find and measure branches: between end-points and junctions.
		 */

		final BranchBuilder branchBuilder = new BranchBuilder( taggedSkeleton, junctions );
		final List< Branch > branches = branchBuilder.process();

		/*
		 * Return
		 */

		return new SkeletonAnalysis( taggedSkeleton, junctions, branches );
	}

	private Img< UnsignedByteType > createTaggedSkeleton( final RandomAccessible< T > skeleton, final Interval interval )
	{

		final Img< UnsignedByteType > taggedSkeleton = Util.getArrayOrCellImgFactory( interval, new UnsignedByteType() ).create( interval );
		final RandomAccess< UnsignedByteType > raTag = taggedSkeleton.randomAccess( interval );

		final Cursor< T > cursor = Views.iterable( Views.interval( skeleton, interval ) ).localizingCursor();

		final NeighborhoodsAccessible< T > neighborhoods = new RectangleShape( 1, true ).neighborhoodsRandomAccessible( skeleton );
		final RandomAccess< Neighborhood< T > > ra = neighborhoods.randomAccess( Intervals.expand( interval, 1 ) );

		while ( cursor.hasNext() )
		{
			cursor.fwd();
			if ( cursor.get().getRealDouble() == 0. )
				continue;

			ra.setPosition( cursor );
			final Neighborhood< T > neighborhood = ra.get();
			int nNeighbors = 0;
			for ( final T t : neighborhood )
				if ( t.getRealDouble() != 0 )
					nNeighbors++;

			raTag.setPosition( cursor );
			if ( nNeighbors == 1 )
				raTag.get().set( END_POINT_TAG );
			else if ( nNeighbors == 2 )
				raTag.get().set( SLAB_TAG );
			else if ( nNeighbors > 2 )
				raTag.get().set( JUNCTION_TAG_TEMP );

		}

		/*
		 * Remove end-points on the border of the image.
		 */

		removeBorderEndPoints( taggedSkeleton );

		return taggedSkeleton;
	}

	private void removeBorderEndPoints( final Img< UnsignedByteType > taggedSkeleton )
	{
		for ( int dFixed = 0; dFixed < taggedSkeleton.numDimensions(); dFixed++ )
		{
			final IntervalView< UnsignedByteType > hyperSlice1 = Views.hyperSlice( taggedSkeleton, dFixed, taggedSkeleton.min( dFixed ) );
			for ( final UnsignedByteType p : hyperSlice1 )
				if ( p.get() == END_POINT_TAG )
					p.set( SLAB_TAG );

			final IntervalView< UnsignedByteType > hyperSlice2 = Views.hyperSlice( taggedSkeleton, dFixed, taggedSkeleton.max( dFixed ) );
			for ( final UnsignedByteType p : hyperSlice2 )
				if ( p.get() == END_POINT_TAG )
					p.set( SLAB_TAG );
		}
	}

	private List< Junction > groupJunctions( final Img< UnsignedByteType > taggedSkeleton )
	{
		final NeighborhoodsAccessible< UnsignedByteType > neighborhoods = new RectangleShape( 1, true ).neighborhoodsRandomAccessible( taggedSkeleton );
		final RandomAccess< Neighborhood< UnsignedByteType > > raNeighborhood = neighborhoods.randomAccess( Intervals.expand( taggedSkeleton, 1 ) );
		final Cursor< UnsignedByteType > cursor = taggedSkeleton.localizingCursor();

		// Start at 1 so that we are not confused with bg = 0.
		int junctionID = 1;
		final List< Junction > junctions = new ArrayList<>();
		while ( cursor.hasNext() )
		{
			cursor.fwd();
			if ( cursor.get().get() != JUNCTION_TAG_TEMP )
				continue;

			// Mark as visited.
			cursor.get().set( JUNCTION_TAG );
			final Point start = Point.wrap( new long[ taggedSkeleton.numDimensions() ] );
			start.setPosition( cursor );

			final ArrayDeque< Point > queue = new ArrayDeque<>();
			queue.add( start );

			final Junction junction = new Junction( junctionID++, start );
			junctions.add( junction );

			// Visit neighborhood.
			while ( !queue.isEmpty() )
			{
				final Point point = queue.pop();
				junction.increment( point );

				// Explore neighborhood.
				raNeighborhood.setPosition( point );
				final Neighborhood< UnsignedByteType > neighborhood = raNeighborhood.get();
				final Cursor< UnsignedByteType > lc = neighborhood.localizingCursor();
				while ( lc.hasNext() )
				{
					lc.fwd();
					if ( lc.get().get() == JUNCTION_TAG_TEMP )
					{
						lc.get().set( JUNCTION_TAG );
						final Point j = Point.wrap( new long[ taggedSkeleton.numDimensions() ] );
						j.setPosition( lc );
						queue.add( j );
					}
				}
			}
		}
		return junctions;
	}

	public static final < T extends RealType< T > > SkeletonAnalysis analyze( final RandomAccessible< T > skeleton, final Interval interval )
	{
		final SkeletonAnalyzer< T > analyzer = new SkeletonAnalyzer<>();
		return analyzer.calculate( skeleton, interval );
	}

	public static final < T extends RealType< T > > SkeletonAnalysis analyze( final RandomAccessibleInterval< T > skeleton, final Interval interval )
	{
		return analyze( Views.extendZero( skeleton ), interval );
	}

	public static final < T extends RealType< T > > SkeletonAnalysis analyze( final RandomAccessibleInterval< T > skeleton )
	{
		return analyze( skeleton, skeleton );
	}
}
