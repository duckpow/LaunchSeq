//Launcher/Stepsequenser class for launchpad and supercollider 3.6+
//Made by Duckpow in 2014
//Use at own risk

// Superclass. Main purpose; midi communication.
Launcher {
	var <in1;
	var <in2;
	var <out;

	// call super and init function
	*new{
		^super.new.initLauncher;
	}

	// Own init function
	initLauncher{
		//Check midi and init
		if (MIDIClient.sources==nil, {
			MIDIClient.init;},{});

		// Search for devices and connect
		MIDIIn.connectAll;
		out = MIDIOut.newByName("Launchpad", "Launchpad"); //.latency_(0.01);
		// Show the launchpad works
		this.allLEDOn;
		this.allLEDOff;
	}

	//Convinience method for turning on all LED's
	allLEDOn{
		127.do{arg i; this.out.noteOn(0,i,127);}
	}

	//Convinience (PANIC) method for turning off all LED's
	allLEDOff{
		127.do{arg i; this.out.noteOn(0,i,0);}
	}
}

// Main class.
LaunchSeq : Launcher {
	var <startNote;
	var <steps;
	var <stepSize;
	var <led;
	var <player;
	var <clock;
	var <midiPlayer;
	var <midiInterface;
	var <players;
	var <instruments;
	var <notes;

	// "Borrowed" from Axel Baesler SoftStepSeq.sc
	// This is genious!
	*initClass{ // add Event type callMethod to default Event
		var preIndex;
		var track;

		StartUp.add({
			Event.addEventType(\callMethod, { //not yet in use
				if (~argument != 0 or: {~allowZero != 0}, {
					if (~sendArg == true, {
						~receiver.perform(~meth, ~argument);
					},{
						~receiver.perform(~meth);
					});
				});
			});

			// Specialized event type to replace \midi due to it's limitations
			// \amp takes the full range 0-127 instead of midi's 0-1
			Event.addEventType(\midiSeqOut, {
				~midiout.noteOn(~chan,~midinote,~amp);
				preIndex = ((~midinote%16)-1)%~steps;
				track = (~midinote/16).floor;
				if(~notesArray[track][preIndex].asBoolean,{
					~midiout.noteOn(~chan,((track*16)+preIndex),127);
				},{
					~midiout.noteOn(~chan,((track*16)+preIndex),0);
				});
			});
		});
	}

	//construct with args and
	*new{|tempo = 120|
		^super.new.init(tempo);
	}

	init{arg t;
		//Num steps for each track
		steps = 8!8;
		// individual stepsizes for each track. Starts at quaternotes
		stepSize = Array.fill(8,{0.25});

		//set tempo of clock
		TempoClock.default.tempo = t/60;
		//Initialize array to hold patterns
		players = Array.newClear(8);
		instruments = Array.newClear(8);

		//Initialize notes array
		notes = 0!8!8;

		//Midi def to create, start and stop patterns
		midiPlayer = MIDIdef.noteOn(\mPlayer,{
			arg vel, nn, chan, src;
			var temp;
			temp = players[((nn-8)/16).floor];
			if( temp == nil,{
				this.sequencer(nn);
				},{
				if(temp.isPlaying,{
					temp.stop;
					this.trackLEDreset(((nn-8)/16).floor);
					},{
					temp.reset;
					temp.play(quant:1);
				});
			});
		},[8,24,40,56,72,88,104,120],0);

		// MIDI grid interface. Sets note's on/off
		midiInterface = MIDIdef.noteOn(\mInterface,{
			arg vel, nn, chan, src;
			var index;
			var track;
			if([8,24,40,56,72,88,104,120].detect({ arg item, i; item == nn })==nil,{
				index = nn%16;
				track = (nn/16).floor;
				if(notes[track][index].asBoolean,{
					notes[track][index] = 0;
					out.noteOn(chan,nn,0);
				},{
					notes[track][index] = 1;
					out.noteOn(chan,nn,127);
				});
			},{});
		},chan: 0);


		"LaunchSeq ready".postln;
	}

	// set tempo i bpm
	tempo{arg bpm;
		TempoClock.default.tempo = (bpm/60);
	}

	// change dur/delta of Pbinds by a factor
	changeTempoFactor{arg factor, track;
		var temp;
		if(track.isNil,{
			stepSize.do{arg item, i; temp = item[i]*factor; stepSize[i] = temp;}
		},{
			temp = stepSize[track]*factor;
			stepSize[track] = temp;
		});
	}

	// Set dur/delta of Pbinds.
	setTempoSubDiv{arg fraction, track;
		if(track.isNil,{
			stepSize = fraction!8;
		},{
			stepSize[track]=fraction;
		});
	}


	// set seq length
	seqLength {arg length, track;
		if(track.isNil,{
			Task({steps = length!8}).play(quant: [1,0.1]);
		},{
			Task({steps[track] = length}).play(quant: [1,0.1]);
		})
	}

	// Create sequencer
	sequencer {arg nn, l=8;
		var sNote;
		var track;
		var arr;
		var length;
		length = l;
		sNote = (nn-8);
		track = (sNote/16).floor;
		arr = Array.fill(8,{arg i; i+sNote});
		this.bindSequencer(track,length,arr);
		players[track].play(quant: 1);
	}

	// Bind synth def to a sequencer
	setInstrument {arg track, type, synthName ... args;
		var arguments;
		var arr;
		//Check if track is active
		if(players[track].isPlaying,{"Stop track before changing sounds".postln},{
			//Check arguments
			args.postln;
			if(args.size.odd,{"parameters and initial values must form a even array".postln; ^nil},{});
			//Free old track
			instruments[track].free;
			// Create arguments for Pbind
			arr = notes[track];
			// array to hold arguments for Pbind. Change size?
			arguments = Array.new(16);
			arguments = arguments.addAll([\instrument, synthName]);
			arguments = arguments.addAll([\on, Pn(Pfin({steps[track]},Pif(Pseq(arr,inf).coin,1,Rest)))]);
			arguments = arguments.addAll(args);
			arguments = arguments.addAll([\dur, stepSize[track]]);
			// Stor Pbind in instruments array
			instruments[track] = Pbind(*arguments);
			// Create sequencer
			this.bindSequencer(track,steps[track],Array.fill(8,{arg i; i+(16*track)}))
		});
	}

	// Combine Pbinds to sequencer.
	bindSequencer { arg track, length, arr;
		//Make sure the old one i free'd
		players[track].free;
		// Combine pbinds for instrument and visual feedback
		players[track] = Ppar([Pbind(
			\type, \midiSeqOut,
			\midiout, out,
			\notesArray, notes,
			\steps, Pfunc({steps[track]}),
			\chan, 0,
			\midinote, Pn(Pfin({steps[track]},Pseq(arr, inf)),inf),
			\amp, 32, // set color
			\stretch, 1,
			\dur, Pfunc({stepSize[track]})
		),instruments[track]]).asEventStreamPlayer; //stored as eventstream but not played
	}

	//Update all LED's (panic option)
	allLEDReset {
		notes.do{|item, i| item.do{|item, j| out.noteOn(0,(i*16)+j,item*127)}};

		//notes.do{|i, index| i.do{|j| out.noteOn(0,((index*16)+j),(notes[index][j]*127))}}; //wrong?
	}

	//Update 1 track. Used when a track is stopped
	trackLEDreset {arg track;
		notes[track].do{|i, index| out.noteOn(0,((track*16)+index),i*127)};
	}

	// Plays specific or all sequencers
	play {arg tracks;
		if(tracks.isNil,{
			players.do{arg i; i.reset; i.play(quant: 1);};
		},{
			if(tracks.size.asBoolean,{ //int.size returns 0
				tracks.do{arg i; players[i].play(quant: 1);};
			},{
				players[tracks].play(quant: 1);
			});
		});
	}

	// Stops all sequencers
	stop {arg tracks;
		//tracks.postln;
		if(tracks.isNil,{
			players.do{arg i; i.stop;};
			this.allLEDReset; // don't work???
		},{
				if(tracks.size.asBoolean,{ //int.size returns 0
					tracks.do{arg i; players[i].stop; this.trackLEDreset(i)};
				},{
					players[tracks].stop;
					this.trackLEDreset(tracks);
				});
		});

	}

	//Make a free method!!!
}

