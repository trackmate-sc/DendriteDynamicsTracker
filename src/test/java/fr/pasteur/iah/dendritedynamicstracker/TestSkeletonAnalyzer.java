package fr.pasteur.iah.dendritedynamicstracker;

import java.io.IOException;

import net.imagej.Dataset;
import net.imagej.ImageJ;
import net.imglib2.RandomAccessibleInterval;

import fr.pasteur.iah.dendritedynamicstracker.skeleton.SkeletonAnalysis;
import fr.pasteur.iah.dendritedynamicstracker.skeleton.SkeletonAnalysis.Branch;
import fr.pasteur.iah.dendritedynamicstracker.skeleton.SkeletonAnalysis.Junction;
import fr.pasteur.iah.dendritedynamicstracker.skeleton.SkeletonAnalyzer;

public class TestSkeletonAnalyzer
{

	@SuppressWarnings( { "rawtypes", "unchecked" } )
	public static void main( final String[] args ) throws IOException
	{
		final ImageJ ij = new ImageJ();
		ij.launch( args );

		final String path = "samples/Example3-singleframe.tif";
		final Dataset dataset = ( Dataset ) ij.io().open( path );
		ij.ui().show( dataset );

		System.out.println( "Running skeleton analysis." );
		final SkeletonAnalysis analysis = SkeletonAnalyzer.analyze( ( RandomAccessibleInterval ) dataset.getImgPlus() );
		ij.ui().show( analysis.taggedSkeleton );

		System.out.println( "\nJunctions: " );
		for ( final Junction junction : analysis.getJunctions() )
			System.out.println( " - " + junction );

		System.out.println( "\nBranches: " );
		for ( final Branch branch : analysis.getBranches() )
			System.out.println( " - " + branch );
		
		System.out.println( "\nBranches without junctions:" );
		analysis.getBranches().stream()
				.filter( b -> ( b.getJunction() == null  ) )
				.forEach( b -> System.out.println( " - " + b ) );

		System.out.println( "Done." );
	}
}
