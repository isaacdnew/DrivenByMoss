1. Launch Bitwig; open project. I'm omitting these startup lines since they're not relevant.

2. Record and finish a layer on the group in col6:

[LOOP] toggleColumnArm -> armed=true col=6 bankScroll=0 groupPos=6 validity=VALID armed=true | child[0]=AUDIO('Output Monitor') child[1]=AUDIO('Template') child[2]=AUDIO('Layer 1') child[3]=MASTER('Audio A (FancyLoop) Master') child[4]=ABSENT('') 
[LOOP] handlePad(scene=0) col=6 bankScroll=0 groupPos=6 validity=VALID armed=true | child[0]=AUDIO('Output Monitor') child[1]=AUDIO('Template') child[2]=AUDIO('Layer 1') child[3]=MASTER('Audio A (FancyLoop) Master') child[4]=ABSENT('') 
[LOOP]   -> branch RECORD (deferred-duplicate): target=child[2] name='Layer 1' pos=2 | groupSlot playing=false playingQueued=false hasContent=false
[LOOP]      startRecording on track pos=2 name='Layer 1' slotPos=0 (recording=false recQueued=false)
[LOOP]      post-record (no duplicate; next staging created on a later settled rescan) col=6 bankScroll=0 groupPos=6 validity=VALID armed=true | child[0]=AUDIO('Output Monitor') child[1]=AUDIO('Template') child[2]=AUDIO('Layer 1') child[3]=MASTER('Audio A (FancyLoop) Master') child[4]=ABSENT('') 
[LOOP] rescan #351 UNSETTLED(re-check queued) bankScroll=0 | col4:VALID groupPos=4 child[2]=AUDIO 'Layer 1' | col5:VALID groupPos=5 child[2]=AUDIO 'Layer 1' | col6:VALID groupPos=6 child[2]=AUDIO 'Layer 1',content
[LOOP] maintainStagingLayer col=6: CREATE staging layer -> duplicating template (child[2]=AUDIO 'Layer 1' content=true)
[LOOP] saveSelection: pinned at position 7 (live selection position 7)
[LOOP] restore-selection check: live=7 pinned=7
[LOOP] rescan #352 settled bankScroll=0 | col4:VALID groupPos=4 child[2]=AUDIO 'Layer 1' | col5:VALID groupPos=5 child[2]=AUDIO 'Layer 1' | col6:VALID groupPos=6 child[2]=AUDIO 'Layer 1',content
[LOOP] restore-selection check: live=7 pinned=7
[LOOP] rescan #353 settled bankScroll=0 | col4:VALID groupPos=4 child[2]=AUDIO 'Layer 1' | col5:VALID groupPos=5 child[2]=AUDIO 'Layer 1' | col6:VALID groupPos=6 child[2]=AUDIO 'Layer 1',content
[LOOP] restore-selection check: live=7 pinned=7
[LOOP] rescan #354 settled bankScroll=0 | col4:VALID groupPos=4 child[2]=AUDIO 'Layer 1' | col5:VALID groupPos=5 child[2]=AUDIO 'Layer 1' | col6:VALID groupPos=6 child[2]=AUDIO 'Layer 1',content
[LOOP] rescan #355 UNSETTLED(re-check queued) bankScroll=0 | col4:VALID groupPos=4 child[2]=AUDIO 'Layer 1' | col5:VALID groupPos=5 child[2]=AUDIO 'Layer 1' | col6:VALID groupPos=6 child[2]=AUDIO 'Layer 1',content
[LOOP] rescan #356 UNSETTLED(re-check queued) bankScroll=0 | col4:VALID groupPos=4 child[2]=AUDIO 'Layer 1' | col5:VALID groupPos=5 child[2]=AUDIO 'Layer 1' | col6:VALID groupPos=6 child[2]=AUDIO 'Layer 1',content
[LOOP] rescan #357 UNSETTLED(re-check queued) bankScroll=0 | col4:VALID groupPos=4 child[2]=AUDIO 'Layer 1' | col5:VALID groupPos=5 child[2]=AUDIO 'Layer 1' | col6:VALID groupPos=6 child[2]=AUDIO 'Layer 1',content
[LOOP] rescan #358 UNSETTLED(re-check queued) bankScroll=0 | col4:VALID groupPos=4 child[2]=AUDIO 'Layer 1' | col5:VALID groupPos=5 child[2]=AUDIO 'Layer 1' | col6:VALID groupPos=6 child[2]=AUDIO 'Layer 1',content
[LOOP] rescan #359 UNSETTLED(re-check queued) bankScroll=0 | col4:VALID groupPos=4 child[2]=AUDIO '' | col5:VALID groupPos=5 child[2]=AUDIO 'Layer 1' | col6:VALID groupPos=6 child[2]=AUDIO 'Layer 1',content
[LOOP] rescan #360 UNSETTLED(re-check queued) bankScroll=0 | col4:VALID groupPos=4 child[2]=UNKNOWN '' | col5:VALID groupPos=5 child[2]=AUDIO 'Layer 1' | col6:VALID groupPos=6 child[2]=AUDIO 'Layer 1',content
[LOOP] rescan #361 UNSETTLED(re-check queued) bankScroll=0 | col4:VALID groupPos=4 child[2]=ABSENT | col5:VALID groupPos=5 child[2]=AUDIO 'Layer 1' | col6:VALID groupPos=6 child[2]=AUDIO 'Layer 1',content
[LOOP] rescan #362 UNSETTLED(re-check queued) bankScroll=0 | col4:VALID groupPos=4 child[2]=ABSENT | col5:VALID groupPos=5 child[2]=AUDIO 'Layer 1' | col6:VALID groupPos=6 child[2]=AUDIO 'Layer 1',content
[LOOP] rescan #363 UNSETTLED(re-check queued) bankScroll=0 | col4:VALID groupPos=4 child[2]=ABSENT | col5:VALID groupPos=5 child[2]=AUDIO 'Layer 1' | col6:VALID groupPos=6 child[2]=AUDIO 'Layer 1',content
[LOOP] rescan #364 UNSETTLED(re-check queued) bankScroll=0 | col4:MISCONFIGURED groupPos=4 child[2]=ABSENT | col5:VALID groupPos=5 child[2]=AUDIO 'Layer 1' | col6:VALID groupPos=6 child[2]=AUDIO 'Layer 1',content
[LOOP] rescan #365 UNSETTLED(re-check queued) bankScroll=0 | col4:MISCONFIGURED groupPos=4 child[2]=ABSENT | col5:VALID groupPos=5 child[2]=AUDIO 'Layer 1' | col6:VALID groupPos=6 child[2]=AUDIO 'Layer 1',content
[LOOP] rescan #366 UNSETTLED(re-check queued) bankScroll=0 | col4:MISCONFIGURED groupPos=4 child[2]=ABSENT | col5:VALID groupPos=5 child[2]=AUDIO 'Layer 1' | col6:VALID groupPos=6 child[2]=AUDIO 'Layer 1',content
[LOOP] rescan #367 UNSETTLED(re-check queued) bankScroll=0 | col4:MISCONFIGURED groupPos=4 child[2]=ABSENT | col5:VALID groupPos=5 child[2]=AUDIO '' | col6:VALID groupPos=6 child[2]=AUDIO 'Layer 1',content
[LOOP] rescan #368 UNSETTLED(re-check queued) bankScroll=0 | col4:MISCONFIGURED groupPos=4 child[2]=ABSENT | col5:VALID groupPos=5 child[2]=UNKNOWN '' | col6:VALID groupPos=6 child[2]=AUDIO 'Layer 1',content
[LOOP] rescan #369 UNSETTLED(re-check queued) bankScroll=0 | col4:MISCONFIGURED groupPos=4 child[2]=ABSENT | col5:VALID groupPos=5 child[2]=ABSENT | col6:VALID groupPos=6 child[2]=AUDIO 'Layer 1',content
[LOOP] rescan #370 UNSETTLED(re-check queued) bankScroll=0 | col4:MISCONFIGURED groupPos=4 child[2]=ABSENT | col5:VALID groupPos=5 child[2]=ABSENT | col6:VALID groupPos=6 child[2]=AUDIO 'Layer 1',content
[LOOP] rescan #371 UNSETTLED(re-check queued) bankScroll=0 | col4:MISCONFIGURED groupPos=4 child[2]=ABSENT | col5:VALID groupPos=13 child[2]=ABSENT
[LOOP] rescan #372 UNSETTLED(re-check queued) bankScroll=0 | col4:MISCONFIGURED groupPos=4 child[2]=ABSENT | col5:MISCONFIGURED groupPos=13 child[2]=ABSENT
[LOOP] rescan #373 UNSETTLED(re-check queued) bankScroll=0 | col4:MISCONFIGURED groupPos=4 child[2]=ABSENT | col5:MISCONFIGURED groupPos=13 child[2]=ABSENT
[LOOP] rescan #374 UNSETTLED(re-check queued) bankScroll=0 | col4:MISCONFIGURED groupPos=4 child[2]=ABSENT | col5:MISCONFIGURED groupPos=13 child[2]=ABSENT
[LOOP] rescan #375 UNSETTLED(re-check queued) bankScroll=0 | col4:MISCONFIGURED groupPos=4 child[2]=ABSENT | col5:MISCONFIGURED groupPos=13 child[2]=ABSENT
[LOOP] rescan #376 UNSETTLED(re-check queued) bankScroll=0 | col4:MISCONFIGURED groupPos=4 child[2]=ABSENT | col5:MISCONFIGURED groupPos=13 child[2]=ABSENT
[LOOP] rescan #377 UNSETTLED(re-check queued) bankScroll=0 | col4:MISCONFIGURED groupPos=4 child[2]=ABSENT | col5:MISCONFIGURED groupPos=13 child[2]=ABSENT
[LOOP] rescan #378 UNSETTLED(re-check queued) bankScroll=0 | col4:MISCONFIGURED groupPos=4 child[2]=ABSENT | col5:MISCONFIGURED groupPos=13 child[2]=ABSENT
[LOOP] rescan #379 UNSETTLED(re-check queued) bankScroll=0 | col4:MISCONFIGURED groupPos=4 child[2]=ABSENT | col5:MISCONFIGURED groupPos=13 child[2]=ABSENT
[LOOP] rescan #380 UNSETTLED(re-check queued) bankScroll=0 | col4:MISCONFIGURED groupPos=4 child[2]=ABSENT | col5:MISCONFIGURED groupPos=13 child[2]=ABSENT
[LOOP] rescan #381 UNSETTLED(re-check queued) bankScroll=0 | col4:MISCONFIGURED groupPos=4 child[2]=ABSENT | col5:MISCONFIGURED groupPos=13 child[2]=ABSENT
[LOOP] restore-selection check: live=7 pinned=7
[LOOP] rescan #382 settled bankScroll=0 | col4:MISCONFIGURED groupPos=4 child[2]=ABSENT | col5:MISCONFIGURED groupPos=13 child[2]=ABSENT
[LOOP] restore-selection check: live=7 pinned=7
[LOOP] rescan #383 settled bankScroll=0 | col4:MISCONFIGURED groupPos=4 child[2]=ABSENT | col5:MISCONFIGURED groupPos=13 child[2]=ABSENT
[LOOP] restore-selection check: live=7 pinned=7
[LOOP] rescan #384 settled bankScroll=8 | col4:MISCONFIGURED groupPos=4 child[2]=ABSENT | col5:MISCONFIGURED groupPos=13 child[2]=ABSENT
[LOOP] restore-selection check: live=2 pinned=7
[LOOP] restore-selection: edit stole the selection - re-selecting the saved track
[LOOP] rescan #385 settled bankScroll=8 | col4:MISCONFIGURED groupPos=4 child[2]=ABSENT | col5:MISCONFIGURED groupPos=13 child[2]=ABSENT
[LOOP] rescan #386 settled bankScroll=8 | col4:MISCONFIGURED groupPos=4 child[2]=ABSENT | col5:MISCONFIGURED groupPos=13 child[2]=ABSENT
[LOOP] rescan #387 settled bankScroll=8 | col4:MISCONFIGURED groupPos=4 child[2]=ABSENT | col5:MISCONFIGURED groupPos=13 child[2]=ABSENT
[LOOP] rescan #388 settled bankScroll=8 | col4:MISCONFIGURED groupPos=4 child[2]=ABSENT | col5:MISCONFIGURED groupPos=13 child[2]=ABSENT
[LOOP] rescan #389 settled bankScroll=8 | col4:MISCONFIGURED groupPos=4 child[2]=ABSENT | col5:MISCONFIGURED groupPos=13 child[2]=ABSENT
[LOOP] rescan #390 settled bankScroll=8 | col5:MISCONFIGURED groupPos=13 child[2]=ABSENT
[LOOP] rescan #391 settled bankScroll=8 | col5:MISCONFIGURED groupPos=13 child[2]=ABSENT
[LOOP] rescan #392 settled bankScroll=8 | (no looper columns visible)
[LOOP] rescan #393 settled bankScroll=8 | (no looper columns visible)
[LOOP] rescan #394 settled bankScroll=8 | (no looper columns visible)
[LOOP] rescan #395 settled bankScroll=8 | (no looper columns visible)
[LOOP] rescan #396 settled bankScroll=8 | (no looper columns visible)
[LOOP] rescan #397 settled bankScroll=8 | (no looper columns visible)
[LOOP] rescan #398 settled bankScroll=8 | (no looper columns visible)
[LOOP] rescan #399 settled bankScroll=8 | (no looper columns visible)
[LOOP] rescan #400 settled bankScroll=8 | (no looper columns visible)
[LOOP] rescan #401 settled bankScroll=8 | (no looper columns visible)
[LOOP] rescan #402 settled bankScroll=8 | col4:MISCONFIGURED groupPos=12 child[2]=ABSENT
[LOOP] rescan #403 settled bankScroll=8 | col4:MISCONFIGURED groupPos=12 child[2]=ABSENT
[LOOP] rescan #404 UNSETTLED(re-check queued) bankScroll=8 | col4:MISCONFIGURED groupPos=12 child[2]=ABSENT
[LOOP] rescan #405 settled bankScroll=8 | col4:MISCONFIGURED groupPos=12 child[2]=ABSENT
[LOOP] rescan #406 UNSETTLED(re-check queued) bankScroll=8 | col4:MISCONFIGURED groupPos=12 child[2]=ABSENT
[LOOP] rescan #407 settled bankScroll=8 | col4:VALID groupPos=12 child[2]=ABSENT
[LOOP] rescan #408 UNSETTLED(re-check queued) bankScroll=8 | col4:VALID groupPos=12 child[2]=ABSENT
[LOOP] rescan #409 settled bankScroll=8 | col4:VALID groupPos=12 child[2]=ABSENT
[LOOP] rescan #410 UNSETTLED(re-check queued) bankScroll=8 | col4:VALID groupPos=12 child[2]=AUDIO 'Layer 1'
[LOOP] rescan #411 settled bankScroll=8 | col4:VALID groupPos=12 child[2]=AUDIO 'Layer 1'
[LOOP] rescan #412 UNSETTLED(re-check queued) bankScroll=8 | col4:VALID groupPos=4 child[2]=AUDIO 'Layer 1' | col5:MISCONFIGURED groupPos=13 child[2]=ABSENT
[LOOP] rescan #413 settled bankScroll=8 | col4:VALID groupPos=4 child[2]=AUDIO 'Layer 1' | col5:MISCONFIGURED groupPos=13 child[2]=ABSENT
[LOOP] rescan #414 UNSETTLED(re-check queued) bankScroll=8 | col4:VALID groupPos=4 child[2]=AUDIO 'Layer 1' | col5:MISCONFIGURED groupPos=13 child[2]=ABSENT
[LOOP] rescan #415 settled bankScroll=8 | col4:VALID groupPos=4 child[2]=AUDIO 'Layer 1' | col5:MISCONFIGURED groupPos=13 child[2]=ABSENT
[LOOP] rescan #416 UNSETTLED(re-check queued) bankScroll=8 | col4:VALID groupPos=4 child[2]=AUDIO 'Layer 1' | col5:MISCONFIGURED groupPos=13 child[2]=ABSENT
[LOOP] rescan #417 settled bankScroll=8 | col4:VALID groupPos=4 child[2]=AUDIO 'Layer 1' | col5:VALID groupPos=13 child[2]=ABSENT
[LOOP] rescan #418 UNSETTLED(re-check queued) bankScroll=8 | col4:VALID groupPos=4 child[2]=AUDIO 'Layer 1' | col5:VALID groupPos=13 child[2]=ABSENT
[LOOP] rescan #419 settled bankScroll=8 | col4:VALID groupPos=4 child[2]=AUDIO 'Layer 1' | col5:VALID groupPos=13 child[2]=ABSENT
[LOOP] rescan #420 UNSETTLED(re-check queued) bankScroll=8 | col4:VALID groupPos=4 child[2]=AUDIO 'Layer 1' | col5:VALID groupPos=13 child[2]=AUDIO 'Layer 1'
[LOOP] rescan #421 settled bankScroll=8 | col4:VALID groupPos=4 child[2]=AUDIO 'Layer 1' | col5:VALID groupPos=13 child[2]=AUDIO 'Layer 1'
[LOOP] rescan #422 UNSETTLED(re-check queued) bankScroll=8 | col4:VALID groupPos=4 child[2]=AUDIO 'Layer 1' | col5:VALID groupPos=5 child[2]=AUDIO 'Layer 1'
[LOOP] rescan #423 settled bankScroll=8 | col4:VALID groupPos=4 child[2]=AUDIO 'Layer 1' | col5:VALID groupPos=5 child[2]=AUDIO 'Layer 1'
[LOOP] rescan #424 UNSETTLED(re-check queued) bankScroll=8 | col4:VALID groupPos=4 child[2]=AUDIO 'Layer 1' | col5:VALID groupPos=5 child[2]=AUDIO 'Layer 1'
[LOOP] rescan #425 settled bankScroll=8 | col4:VALID groupPos=4 child[2]=AUDIO 'Layer 1' | col5:VALID groupPos=5 child[2]=AUDIO 'Layer 1'
[LOOP] rescan #426 UNSETTLED(re-check queued) bankScroll=8 | col4:VALID groupPos=4 child[2]=AUDIO 'Layer 1' | col5:VALID groupPos=5 child[2]=AUDIO 'Layer 1'
[LOOP] rescan #427 settled bankScroll=8 | col4:VALID groupPos=4 child[2]=AUDIO 'Layer 1' | col5:VALID groupPos=5 child[2]=AUDIO 'Layer 1'
[LOOP] rescan #428 UNSETTLED(re-check queued) bankScroll=8 | col4:VALID groupPos=4 child[2]=AUDIO 'Layer 1' | col5:VALID groupPos=5 child[2]=AUDIO 'Layer 1'
[LOOP] rescan #429 settled bankScroll=8 | col4:VALID groupPos=4 child[2]=AUDIO 'Layer 1' | col5:VALID groupPos=5 child[2]=AUDIO 'Layer 1'
[LOOP] rescan #430 UNSETTLED(re-check queued) bankScroll=8 | col4:VALID groupPos=4 child[2]=AUDIO 'Layer 1' | col5:VALID groupPos=5 child[2]=AUDIO 'Layer 1'
[LOOP] rescan #431 settled bankScroll=8 | col4:VALID groupPos=4 child[2]=AUDIO 'Layer 1' | col5:VALID groupPos=5 child[2]=AUDIO 'Layer 1'
[LOOP] rescan #432 settled bankScroll=8 | col4:VALID groupPos=4 child[2]=AUDIO 'Layer 1' | col5:VALID groupPos=5 child[2]=AUDIO 'Layer 1'
[LOOP] rescan #433 UNSETTLED(re-check queued) bankScroll=8 | col4:VALID groupPos=4 child[2]=AUDIO 'Layer 1' | col5:VALID groupPos=5 child[2]=AUDIO 'Layer 1'
[LOOP] rescan #434 settled bankScroll=8 | col4:VALID groupPos=4 child[2]=AUDIO 'Layer 1' | col5:VALID groupPos=5 child[2]=AUDIO 'Layer 1'
[LOOP] rescan #435 UNSETTLED(re-check queued) bankScroll=8 | col4:VALID groupPos=4 child[2]=AUDIO 'Layer 1' | col5:VALID groupPos=5 child[2]=AUDIO 'Layer 1'
[LOOP] rescan #436 settled bankScroll=8 | col4:VALID groupPos=4 child[2]=AUDIO 'Layer 1' | col5:VALID groupPos=5 child[2]=AUDIO 'Layer 1' | col6:VALID groupPos=6 child[2]=AUDIO 'Template'
[LOOP] rescan #437 settled bankScroll=0 | col4:VALID groupPos=4 child[2]=AUDIO 'Layer 1' | col5:VALID groupPos=5 child[2]=AUDIO 'Layer 1' | col6:VALID groupPos=6 child[2]=AUDIO 'Template'
[LOOP] rescan #438 settled bankScroll=0 | col4:VALID groupPos=4 child[2]=AUDIO 'Layer 1' | col5:VALID groupPos=5 child[2]=AUDIO 'Layer 1' | col6:VALID groupPos=6 child[2]=AUDIO 'Template'
[LOOP] rescan #439 UNSETTLED(re-check queued) bankScroll=0 | col4:VALID groupPos=4 child[2]=AUDIO 'Layer 1' | col5:VALID groupPos=5 child[2]=AUDIO 'Layer 1' | col6:VALID groupPos=6 child[2]=AUDIO 'Layer 2'
[LOOP] rescan #440 settled bankScroll=0 | col4:VALID groupPos=4 child[2]=AUDIO 'Layer 1' | col5:VALID groupPos=5 child[2]=AUDIO 'Layer 1' | col6:VALID groupPos=6 child[2]=AUDIO 'Layer 2'
[LOOP] handlePad(scene=0) col=6 -> branch FINISH (relaunch recording layer 'Layer 1')
[LOOP] rescan #441 settled bankScroll=0 | col4:VALID groupPos=4 child[2]=AUDIO 'Layer 1' | col5:VALID groupPos=5 child[2]=AUDIO 'Layer 1' | col6:VALID groupPos=6 child[2]=AUDIO 'Layer 2'


3. At this point, I have the following state:
    - col6: FancyLoop (this track is selected)
        - child0: Monitor
        - child1: Template
        - child2: Layer 2
        - child3: Layer 1

3. Use the delete-last-layer gesture on the APC40:

[LOOP] saveSelection: pinned at position 7 (live selection position 7)
[LOOP] restore-selection check: live=7 pinned=7
[LOOP] rescan #442 settled bankScroll=0 | col4:VALID groupPos=4 child[2]=AUDIO 'Layer 1' | col5:VALID groupPos=5 child[2]=AUDIO 'Layer 1' | col6:VALID groupPos=6 child[2]=AUDIO 'Layer 2'
[LOOP] restore-selection check: live=7 pinned=7
[LOOP] rescan #443 settled bankScroll=0 | col4:VALID groupPos=4 child[2]=AUDIO 'Layer 1' | col5:VALID groupPos=5 child[2]=AUDIO 'Layer 1' | col6:VALID groupPos=6 child[2]=AUDIO 'Layer 2'
[LOOP] restore-selection check: live=7 pinned=7
[LOOP] rescan #444 settled bankScroll=0 | col4:VALID groupPos=4 child[2]=AUDIO 'Layer 1' | col5:VALID groupPos=5 child[2]=AUDIO 'Layer 1' | col6:VALID groupPos=6 child[2]=AUDIO 'Layer 2'
[LOOP] restore-selection check: live=7 pinned=7
[LOOP] rescan #445 settled bankScroll=0 | col4:VALID groupPos=4 child[2]=AUDIO 'Layer 1' | col5:VALID groupPos=5 child[2]=AUDIO 'Layer 1' | col6:VALID groupPos=6 child[2]=AUDIO 'Layer 2'
[LOOP] restore-selection check: live=7 pinned=7
[LOOP] rescan #446 settled bankScroll=0 | col4:VALID groupPos=4 child[2]=AUDIO 'Layer 1' | col5:VALID groupPos=5 child[2]=AUDIO 'Layer 1' | col6:VALID groupPos=6 child[2]=AUDIO 'Layer 2'
[LOOP] restore-selection check: live=7 pinned=7
[LOOP] rescan #447 settled bankScroll=0 | col4:VALID groupPos=4 child[2]=AUDIO 'Layer 1' | col5:VALID groupPos=5 child[2]=AUDIO 'Layer 1' | col6:VALID groupPos=6 child[2]=AUDIO 'Layer 2'
[LOOP] restore-selection check: live=7 pinned=7
[LOOP] rescan #448 settled bankScroll=0 | col4:VALID groupPos=4 child[2]=AUDIO 'Layer 1' | col5:VALID groupPos=5 child[2]=AUDIO 'Layer 1' | col6:VALID groupPos=6 child[2]=AUDIO 'Layer 2'
[LOOP] rescan #449 UNSETTLED(re-check queued) bankScroll=0 | col4:VALID groupPos=4 child[2]=AUDIO 'Layer 1' | col5:VALID groupPos=5 child[2]=AUDIO 'Layer 1' | col6:VALID groupPos=6 child[2]=AUDIO 'Layer 2'
[LOOP] rescan #450 UNSETTLED(re-check queued) bankScroll=0 | col4:VALID groupPos=4 child[2]=AUDIO 'Layer 1' | col5:VALID groupPos=5 child[2]=AUDIO 'Layer 1' | col6:VALID groupPos=6 child[2]=AUDIO 'Layer 2'
[LOOP] rescan #451 UNSETTLED(re-check queued) bankScroll=0 | col4:VALID groupPos=4 child[2]=AUDIO 'Layer 1' | col5:VALID groupPos=5 child[2]=AUDIO 'Layer 1' | col6:VALID groupPos=6 child[2]=AUDIO 'Layer 2'
[LOOP] rescan #452 UNSETTLED(re-check queued) bankScroll=0 | col4:VALID groupPos=4 child[2]=AUDIO 'Layer 1' | col5:VALID groupPos=5 child[2]=AUDIO 'Layer 1' | col6:VALID groupPos=6 child[2]=AUDIO 'Layer 2'
[LOOP] rescan #453 UNSETTLED(re-check queued) bankScroll=0 | col4:VALID groupPos=4 child[2]=AUDIO '' | col5:VALID groupPos=5 child[2]=AUDIO 'Layer 1' | col6:VALID groupPos=6 child[2]=AUDIO 'Layer 2'
[LOOP] rescan #454 UNSETTLED(re-check queued) bankScroll=0 | col4:VALID groupPos=4 child[2]=UNKNOWN '' | col5:VALID groupPos=5 child[2]=AUDIO 'Layer 1' | col6:VALID groupPos=6 child[2]=AUDIO 'Layer 2'
[LOOP] rescan #455 UNSETTLED(re-check queued) bankScroll=0 | col4:VALID groupPos=4 child[2]=ABSENT | col5:VALID groupPos=5 child[2]=AUDIO 'Layer 1' | col6:VALID groupPos=6 child[2]=AUDIO 'Layer 2'
[LOOP] rescan #456 UNSETTLED(re-check queued) bankScroll=0 | col4:VALID groupPos=4 child[2]=ABSENT | col5:VALID groupPos=5 child[2]=AUDIO 'Layer 1' | col6:VALID groupPos=6 child[2]=AUDIO 'Layer 2'
[LOOP] rescan #457 UNSETTLED(re-check queued) bankScroll=0 | col4:VALID groupPos=4 child[2]=ABSENT | col5:VALID groupPos=5 child[2]=AUDIO 'Layer 1' | col6:VALID groupPos=6 child[2]=AUDIO 'Layer 2'
[LOOP] restore-selection check: live=7 pinned=7
[LOOP] rescan #458 settled bankScroll=0 | col5:VALID groupPos=5 child[2]=AUDIO 'Layer 1' | col6:VALID groupPos=6 child[2]=AUDIO 'Layer 2'
[LOOP] restore-selection check: live=7 pinned=7
[LOOP] rescan #459 settled bankScroll=0 | col6:VALID groupPos=6 child[2]=AUDIO 'Layer 2'
[LOOP] rescan #460 UNSETTLED(re-check queued) bankScroll=0 | col6:VALID groupPos=6 child[2]=AUDIO 'Layer 2'
[LOOP] rescan #461 UNSETTLED(re-check queued) bankScroll=0 | col6:VALID groupPos=6 child[2]=AUDIO 'Layer 2'
[LOOP] rescan #462 UNSETTLED(re-check queued) bankScroll=0 | col6:VALID groupPos=6 child[2]=AUDIO 'Layer 2'
[LOOP] rescan #463 UNSETTLED(re-check queued) bankScroll=0 | col6:VALID groupPos=6 child[2]=AUDIO 'Layer 2'
[LOOP] rescan #464 UNSETTLED(re-check queued) bankScroll=0 | col6:VALID groupPos=6 child[2]=AUDIO 'Layer 2'
[LOOP] rescan #465 UNSETTLED(re-check queued) bankScroll=0 | col6:VALID groupPos=6 child[2]=AUDIO 'Layer 2'
[LOOP] rescan #466 UNSETTLED(re-check queued) bankScroll=0 | col6:VALID groupPos=6 child[2]=AUDIO 'Layer 2'
[LOOP] rescan #467 UNSETTLED(re-check queued) bankScroll=0 | col6:VALID groupPos=6 child[2]=AUDIO 'Layer 2'
[LOOP] rescan #468 UNSETTLED(re-check queued) bankScroll=0 | col6:VALID groupPos=6 child[2]=AUDIO 'Layer 2'
[LOOP] restore-selection check: live=7 pinned=7
[LOOP] rescan #469 settled bankScroll=0 | (no looper columns visible)
[LOOP] rescan #470 UNSETTLED(re-check queued) bankScroll=0 | (no looper columns visible)
[LOOP] rescan #471 UNSETTLED(re-check queued) bankScroll=0 | (no looper columns visible)
[LOOP] rescan #472 UNSETTLED(re-check queued) bankScroll=0 | (no looper columns visible)
[LOOP] rescan #473 UNSETTLED(re-check queued) bankScroll=0 | (no looper columns visible)
[LOOP] rescan #474 UNSETTLED(re-check queued) bankScroll=0 | (no looper columns visible)
[LOOP] rescan #475 UNSETTLED(re-check queued) bankScroll=0 | (no looper columns visible)
[LOOP] rescan #476 UNSETTLED(re-check queued) bankScroll=0 | (no looper columns visible)
[LOOP] rescan #477 UNSETTLED(re-check queued) bankScroll=0 | (no looper columns visible)
[LOOP] rescan #478 UNSETTLED(re-check queued) bankScroll=0 | (no looper columns visible)
[LOOP] rescan #479 UNSETTLED(re-check queued) bankScroll=0 | (no looper columns visible)
[LOOP] rescan #480 UNSETTLED(re-check queued) bankScroll=0 | (no looper columns visible)
[LOOP] restore-selection check: live=7 pinned=7
[LOOP] rescan #481 settled bankScroll=0 | (no looper columns visible)
[LOOP] restore-selection check: live=7 pinned=7
[LOOP] rescan #482 settled bankScroll=0 | (no looper columns visible)
[LOOP] restore-selection check: live=7 pinned=7
[LOOP] rescan #483 settled bankScroll=8 | (no looper columns visible)
[LOOP] restore-selection check: live=2 pinned=2
[LOOP] rescan #484 settled bankScroll=8 | (no looper columns visible)


4. Now I have this state:
    - col0: Template
    - col1: Layer 2 (this track is selected)
    - col2 thru col7: non-looper tracks

5. Same behavior as last test, just with the selection tracking info.