// may want to change the superclass
FreqSpectrum : Number {
	var <>magnitude, <>phase;

	*new { arg magnitude, phase;
		var mag, pha;

		mag = (magnitude == nil).if({
			Array.fill(phase.size, { 1.0 })
		}, {
			magnitude
		});

		pha = (phase == nil).if({
			Array.zeroFill(magnitude.size)
		}, {
			phase
		});

		^super.newCopyArgs(mag, pha)
	}

	*newComplex { arg complex;
		var polar = complex.asPolar;
		^FreqSpectrum.new(polar.magnitude, polar.phase)
	}

	*logShelf { arg size, freq0, freq1, gainDC, gainNy, sampleRate;
		var f0, f1;
		var delta, sigma;
		var kdc, kny;
		var freqs;
		var mag;

		(freq0.abs < freq1.abs).if({
			f0 = freq0.abs;
			f1 = freq1.abs;
		}, {
			f0 = freq1.abs;
			f1 = freq0.abs;
		});

		delta = (f1 / f0).log2.reciprocal;
		sigma = (f0 * f1).log2;
		kdc = gainDC.dbamp;
		kny = gainNy.dbamp;

		freqs = size.isPowerOfTwo.if({
			size.fftFreqs(sampleRate)
		}, {
			size.dftFreqs(sampleRate)
		});

		mag = freqs.collect({ arg freq;
			var beta, sinBeta2, cosBeta2;
			var freqAbs = freq.abs;

			case
			{ freqAbs <= f0 } {
				kdc
			}
			{ (freqAbs > f0) && (freqAbs < f1) } {
				beta = (pi/4) * (1 + (delta * (sigma - (2 * freqAbs.log2))));  // direct beta
				sinBeta2 = beta.sin.squared;
				cosBeta2 = 1 - sinBeta2;

				kdc.pow(sinBeta2) * kny.pow(cosBeta2)  // return as scale
			}
			{ freqAbs >= f1 } {
				kny
			}
		});

		^FreqSpectrum.new(mag)
	}

	rho { ^magnitude }

	angle { ^phase }
	theta { ^phase }

	// Signal classs / fft related
	real { ^(magnitude * cos(phase)).as(Signal) }
	imag { ^(magnitude * sin(phase)).as(Signal) }

	asFreqSpectrum { ^this }
	asPolar { ^Polar.new(magnitude, phase) }
	asComplex { ^Complex.new(this.real, this.imag) }

	size { ^magnitude.size }

	ks { ^Array.series(magnitude.size) }
	freqs { arg sampleRate;
		^this.ks.kFreqs(this.size, sampleRate)
	}

	peakMagnitude { ^magnitude.maxItem }

	// phase - in place
	linearPhase { arg sym = false;
		var start, step;

		sym.if({
			step = pi.neg * (this.size-1) / this.size;
		}, {
			step = pi.neg;
		});

		phase = this.size.even.if({
			start = step.neg * this.size / 2;  // start with negative freqs
			Array.series(this.size, start, step).rotate((this.size / 2).asInteger)  // then rotate
		}, {
			start = step.neg * (this.size-1) / 2;  // start with negative freqs
			Array.series(this.size, start, step).rotate(((this.size+1) / 2).asInteger)  // then rotate
		})
	}

	// Hilbert minimum phase
	minimumPhase { arg mindb = -120.0;
		var logMag = magnitude.max(magnitude.maxItem * mindb.dbamp).log;
		phase = logMag.as(Signal).analytic.imag.neg.as(Array);  // -1 * Hilbert
	}

	// NOTE: match Hilbert phase rotation
	rotateWave { arg phase;
		var start, step, phaseOffset;

		step = phase;

		this.size.even.if({
			start = step.neg * this.size / 2;  // start with negative freqs
			phaseOffset = Array.series(this.size, start, step);
			phaseOffset = phaseOffset.rotate((this.size / 2).asInteger)  // rotate
		}, {
			start = step.neg * (this.size-1) / 2;  // start with negative freqs
			phaseOffset = Array.series(this.size, start, step);
			phaseOffset = phaseOffset.rotate(((this.size+1) / 2).asInteger)  // rotate
		});

		this.phase = this.phase + phaseOffset
	}

	// NOTE: match Hilbert phase rotation
	rotatePhase { arg phase;
		var phaseOffset = this.freqs.collect({ arg freq;
			freq.isPositive.if({ phase }, { phase.neg })
		});
		this.phase = this.phase + phaseOffset
	}

	/* phase and group delay */

	// set DC to average of bin 1 and N-1
	phaseDelay {
		var num = phase.neg;
		var dem = this.size.isPowerOfTwo.if({  // ks
			this.size.fftFreqs(this.size)
		}, {
			this.size.dftFreqs(this.size)
		});

		^(
			[[num.at(1) / dem.at(1), num.at(this.size - 1) / dem.at(this.size - 1)].mean] ++
			((num.drop(1) / dem.drop(1)))
		) * this.size / 2pi // samples
	}

	groupDelay { arg mindb = -90.0;
		var complex = this.asComplex;
		var ramped, complexr;
		var num, den;
		var minMag, singlBins;

		this.size.isPowerOfTwo.if({
			var rfftSize = this.size / 2 + 1;
			var cosTable = Signal.rfftCosTable(rfftSize);
			var rcomplex = complex.real.fftToRfft(complex.imag);
			var rcomplexr;

			ramped = Array.series(this.size).as(Signal) * rcomplex.real.irfft(rcomplex.imag, cosTable);
			rcomplexr = ramped.rfft(cosTable);
			complexr = rcomplexr.real.rfftToFft(rcomplexr.imag);
		}, {
			ramped = Array.series(this.size).as(Signal) * complex.real.idft(complex.imag).real;
			complexr = ramped.dft(Signal.zeroFill(this.size));
		});

		num = complexr.real.collect({ arg real, i;
			Complex.new(real, complexr.imag.at(i))
		});  // deinterleave
		den = complex.real.collect({ arg real, i;
			Complex.new(real, complex.imag.at(i))
		});  // deinterleave

		// find & replace singularities
		minMag = mindb.dbamp;
		singlBins = magnitude.selectIndices({arg item; item < minMag});
		num.put(singlBins, 0.0);
		den.put(singlBins, 1.0);

		^(num / den).real  // samples
	}

	// allpass - reset magnitude, in place
	allpass {
		magnitude = Array.fill(this.size, { 1.0 })
	}

	// math
	neg { ^FreqSpectrum.new(magnitude, phase + pi) }

	// math - in place
	invert { phase = phase + pi }
	scale { arg scale;
		magnitude = magnitude * scale
	}
	// Normalize the Signal in place such that the maximum absolute peak value is 1.
	normalize { this.scale(this.peakMagnitude.reciprocal) }

	/*
	Consider migrating or otherwise implementing extSequencableCollection:

	maxdb { arg aNumber, adverb; ^this.performBinaryOp('maxdb', aNumber, adverb) }
	mindb { arg aNumber, adverb; ^this.performBinaryOp('mindb', aNumber, adverb) }
	clipdb2 { arg aNumber, adverb; ^this.performBinaryOp('clipdb2', aNumber, adverb) }
	threshdb { arg aNumber, adverb; ^this.performBinaryOp('threshdb', aNumber, adverb) }

	clipdb { arg ... args; ^this.multiChannelPerform('clipdb', *args) }

	*/

	/*
	Implement ...?
	*/

	// // do math as Complex
	// + { arg aNumber;  ^this.asComplex + aNumber  }
	// - { arg aNumber;  ^this.asComplex - aNumber  }
	// * { arg aNumber;  ^this.asComplex * aNumber  }
	// / { arg aNumber;  ^this.asComplex / aNumber  }
	//
	// == { arg aPolar;
	// 	^aPolar respondsTo: #[\rho, \theta] and: {
	// 		rho == aPolar.rho and: { theta == aPolar.theta }
	// 	}
	// }

	// performBinaryOpOnSomething { |aSelector, thing, adverb|
	// 	^thing.asComplex.perform(aSelector, this, adverb)
	// }
	//
	// performBinaryOpOnUGen { arg aSelector, aUGen;
	// 	^Complex.new(
	// 		BinaryOpUGen.new(aSelector, aUGen, this.real),
	// 		BinaryOpUGen.new(aSelector, aUGen, this.imag)
	// 	);
	// }


	hash {
		^magnitude.hash bitXor: phase.hash
	}

	printOn { arg stream;
		stream << "FreqSpectrum( " << magnitude << ", " << phase << " )";
	}

	storeArgs { ^[magnitude, phase] }
}
