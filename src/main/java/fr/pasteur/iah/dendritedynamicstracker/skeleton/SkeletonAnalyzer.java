package fr.pasteur.iah.dendritedynamicstracker.skeleton;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.scijava.plugin.Plugin;

import fr.pasteur.iah.dendritedynamicstracker.skeleton.SkeletonAnalyzer.SkeletonAnalysis;
import fr.pasteur.iah.dendritedynamicstracker.skeleton.SkeletonAnalyzer.SkeletonAnalysis.Junction;

import net.imagej.ops.special.function.AbstractBinaryFunctionOp;
import net.imglib2.Cursor;
import net.imglib2.Interval;
import net.imglib2.Localizable;
import net.imglib2.Point;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealLocalizable;
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

@Plugin( type = SkeletonAnalyzer.class )
public class SkeletonAnalyzer< T extends RealType< T > > extends AbstractBinaryFunctionOp< RandomAccessible< T >, Interval, SkeletonAnalysis >
{

	private static final int END_POINT_TAG = 2;

	private static final int SLAB_TAG = 1;

	private static final int JUNCTION_TAG_TEMP = 255;

	private static final int JUNCTION_TAG = 3;

	@Override
	public SkeletonAnalysis calculate( final RandomAccessible< T > skeleton, final Interval interval )
	{

		/*
		 * Generate skeleton-tagged image.
		 */
		Img< UnsignedByteType > taggedSkeleton = createTaggedSkeleton( skeleton, interval );

		/*
		 * Group junction pixels into junctions.
		 */
		final List< Junction > junctions = groupJunctions( taggedSkeleton );

		/*
		 * Return
		 */

		return new SkeletonAnalysis( taggedSkeleton, junctions );
	}

	private Img< UnsignedByteType > createTaggedSkeleton( RandomAccessible< T > skeleton, Interval interval )
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

	private void removeBorderEndPoints( Img< UnsignedByteType > taggedSkeleton )
	{
		for ( int dFixed = 0; dFixed < taggedSkeleton.numDimensions(); dFixed++ )
		{
			IntervalView< UnsignedByteType > hyperSlice1 = Views.hyperSlice( taggedSkeleton, dFixed, taggedSkeleton.min( dFixed ) );
			for ( UnsignedByteType p : hyperSlice1 )
				if ( p.get() == END_POINT_TAG )
					p.set( SLAB_TAG );

			IntervalView< UnsignedByteType > hyperSlice2 = Views.hyperSlice( taggedSkeleton, dFixed, taggedSkeleton.max( dFixed ) );
			for ( UnsignedByteType p : hyperSlice2 )
				if ( p.get() == END_POINT_TAG )
					p.set( SLAB_TAG );
		}
	}

	private List< Junction > groupJunctions( final Img< UnsignedByteType > taggedSkeleton )
	{
		final NeighborhoodsAccessible< UnsignedByteType > neighborhoods = new RectangleShape( 1, true ).neighborhoodsRandomAccessible( taggedSkeleton );
		final RandomAccess< Neighborhood< UnsignedByteType > > raNeighborhood = neighborhoods.randomAccess( Intervals.expand( taggedSkeleton, 1 ) );
		final Cursor< UnsignedByteType > cursor = taggedSkeleton.localizingCursor();

		int junctionID = 0;
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

			final SkeletonAnalysis.Junction junction = new SkeletonAnalysis.Junction( junctionID++, start );
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

	public static class SkeletonAnalysis
	{

		public final Img< UnsignedByteType > taggedSkeleton;

		private List< Junction > junctions;

		private SkeletonAnalysis( final Img< UnsignedByteType > taggedSkeleton, final List< Junction > junctions )
		{
			this.taggedSkeleton = taggedSkeleton;
			this.junctions = junctions;
		}

		public List< Junction > getJunctions()
		{
			return Collections.unmodifiableList( junctions );
		}

		public static class Junction implements RealLocalizable
		{

			private final int id;

			private double[] sumPos;

			private int nPos = 0;

			private Localizable root;

			private Junction( final int id, final Localizable root )
			{
				this.id = id;
				this.sumPos = new double[ root.numDimensions() ];
				final Point r = Point.wrap( new long[ root.numDimensions() ] );
				r.setPosition( root );
				this.root = r;
			}

			private void increment( final Localizable pos )
			{
				nPos++;
				for ( int d = 0; d < sumPos.length; d++ )
					sumPos[ d ] += pos.getDoublePosition( d );
			}

			public Localizable getRoot()
			{
				return root;
			}

			public int getSize()
			{
				return nPos;
			}

			public int id()
			{
				return id;
			}

			@Override
			public int numDimensions()
			{
				return sumPos.length;
			}

			@Override
			public void localize( final float[] position )
			{
				for ( int d = 0; d < position.length; d++ )
					position[ d ] = ( float ) ( sumPos[ d ] / nPos );
			}

			@Override
			public void localize( final double[] position )
			{
				for ( int d = 0; d < position.length; d++ )
					position[ d ] = sumPos[ d ] / nPos;
			}

			@Override
			public float getFloatPosition( final int d )
			{
				return ( float ) getDoublePosition( d );
			}

			@Override
			public double getDoublePosition( final int d )
			{
				return sumPos[ d ] / nPos;
			}

			@Override
			public String toString()
			{
				return "Junction-" + id + "_N" + nPos + "_@" + Util.printCoordinates( this );
			}
		}
	}
}
