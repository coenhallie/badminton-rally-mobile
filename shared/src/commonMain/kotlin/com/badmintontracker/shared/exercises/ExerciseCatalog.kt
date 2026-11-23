package com.badmintontracker.shared.exercises

/**
 * Curated catalog of original badminton drills and targeted off-court
 * conditioning exercises, keyed by stable slug IDs.
 */
object ExerciseCatalog {
    val all: List<Exercise> = listOf(
        // --- Footwork, Base & Court Movement (14 on-court drills) ---
        Exercise(
            id = "base-mark-6-corner-shadow",
            title = "Base-Mark 6-Corner Shadow",
            category = ExerciseCategory.ON_COURT,
            focus = FocusArea.BASE_RECOVERY,
            prescription = "4 x 45 s · 20 s rest",
            setup = "Solo on half court. Place one flat floor marker 1.4 m behind the short service line on the centre line.",
            steps = listOf(
                "Start balanced in a ready stance straddling the floor marker.",
                "Split-step and move to one of the six singles corners with a controlled shadow swing.",
                "Push off out of the corner and land both feet back across the marker before starting the next corner.",
                "Cycle all six corners at a steady rhythm, prioritizing an active mid-court base over rushing.",
            ),
            coachingCue = "Land balanced over the floor marker before committing to the next corner.",
        ),
        Exercise(
            id = "clear-to-base-multi-shuttle",
            title = "Clear-to-Base Multi-Shuttle",
            category = ExerciseCategory.ON_COURT,
            focus = FocusArea.BASE_RECOVERY,
            prescription = "3 x 15 shuttles",
            setup = "Feeder at the opposite service line with a tube of shuttles; one flat marker at the mid-court base.",
            steps = listOf(
                "Receive a high feed to either rear singles corner and play a deep clear or fast drop.",
                "Use the landing leg to drive forward immediately back onto the mid-court marker.",
                "Take a small split-step on the marker as the feeder prepares the next shuttle.",
            ),
            coachingCue = "Recover forward immediately after contact instead of watching the shuttle from the backcourt.",
        ),
        Exercise(
            id = "net-to-rear-recovery",
            title = "Net-to-Rear Recovery Drill",
            category = ExerciseCategory.ON_COURT,
            focus = FocusArea.COURT_COVERAGE,
            prescription = "3 x 12 reps",
            setup = "Feeder near the net with shuttles. Alternate tight net feeds with high lifts to the rear corners.",
            steps = listOf(
                "Lunge forward to play a controlled net shot from the forecourt.",
                "Push back sharply off the lunging leg past the service line into a balanced mid-court base.",
                "React to the high lift, chasse into the rear corner, and play a balanced overhead clear.",
                "Reset to mid-court depth behind the service line before the next net feed.",
            ),
            coachingCue = "Clear the service line on recovery so the rear corners stay reachable in two steps.",
        ),
        Exercise(
            id = "front-back-2-corner-feed",
            title = "Front-Back 2-Corner Feeding",
            category = ExerciseCategory.ON_COURT,
            focus = FocusArea.COURT_COVERAGE,
            prescription = "4 x 12 shuttles",
            setup = "Feeder on the opposite half alternating low net feeds and deep backcourt feeds down one channel.",
            steps = listOf(
                "Take a tight net shot at the front and immediately push back past the service line.",
                "Pause for a brief split-step in mid-court before moving back for the deep overhead feed.",
                "Play a straight drop or clear from the rear and recover to the same mid-court depth.",
                "Switch channels (forehand side to backhand side) after every set.",
            ),
            coachingCue = "Keep the base behind the service line rather than lingering near the net after front-court shots.",
        ),
        Exercise(
            id = "opposite-side-corner-recovery",
            title = "Wide-Corner Balance Feeding",
            category = ExerciseCategory.ON_COURT,
            focus = FocusArea.LATERAL_BALANCE,
            prescription = "3 x 16 shuttles",
            setup = "Feeder sends two feeds to the dominant side followed by a wide surprise feed to the opposite side.",
            steps = listOf(
                "Handle two consecutive feeds to the crowded side while resetting feet squarely over the centre line.",
                "Read the wide cross-court change of direction on the third feed without crossing feet prematurely.",
                "Play a high defensive clear or tight block from the wide corner and recover cleanly to the centre line.",
            ),
            coachingCue = "Finish every recovery straddling the centre line so neither sideline is left open.",
        ),
        Exercise(
            id = "centre-line-split-reset",
            title = "Centre-Line Split-Step Reset",
            category = ExerciseCategory.ON_COURT,
            focus = FocusArea.LATERAL_BALANCE,
            prescription = "4 x 40 s · 20 s rest",
            setup = "Solo or with a caller on half court. Use the painted centre line as the reset reference.",
            steps = listOf(
                "Begin with one foot on each side of the centre line, knees softly bent.",
                "Move laterally to the left or right sideline with a crisp lunge and simulated drive or block.",
                "Chasse back until the centre line sits cleanly between both shoes, then hit a quick split-step.",
                "Alternate left and right sidelines with unpredictable calls on the final two sets.",
            ),
            coachingCue = "Check foot placement on the centre line on every reset instead of drifting toward the favoured side.",
        ),
        Exercise(
            id = "fatigue-6-corner-shadow",
            title = "Fatigue-State 6-Corner Shadow",
            category = ExerciseCategory.ON_COURT,
            focus = FocusArea.ENDURANCE,
            prescription = "5 x 45 s · 15 s rest",
            setup = "Solo on half court with a flat marker placed at mid-court base depth.",
            steps = listOf(
                "Move at match tempo across random six-corner shadow sequences for 45 seconds.",
                "Hold the exact same recovery depth onto the floor marker during the final 15 seconds of each set.",
                "Keep rest strictly to 15 seconds to simulate late-game cardiovascular load.",
                "Track whether the recovery step reaches the marker cleanly on sets four and five.",
            ),
            coachingCue = "Refuse to drop deeper behind the marker as legs tire in the last two sets.",
        ),
        Exercise(
            id = "cone-touch-base-discipline",
            title = "Cone-Touch Base Discipline",
            category = ExerciseCategory.ON_COURT,
            focus = FocusArea.BASE_RECOVERY,
            prescription = "3 x 15 shuttles",
            setup = "Feeder with shuttles; place a flat non-slip cone at the singles centre base.",
            steps = listOf(
                "Play each random corner feed back over the net with height and margin.",
                "Immediately return to the flat cone and lightly tap or step over the spot before the next feed.",
                "Hold a brief balanced pause over the cone rather than running straight from corner to corner.",
            ),
            coachingCue = "Treat the base cone as a mandatory checkpoint on every single rally shot.",
        ),
        Exercise(
            id = "drop-and-follow-net-attack",
            title = "Drop-and-Follow Net Attack",
            category = ExerciseCategory.ON_COURT,
            focus = FocusArea.COURT_COVERAGE,
            prescription = "3 x 12 pairs",
            setup = "Feeder sends a high clear followed immediately by a loose short return near the net.",
            steps = listOf(
                "Move cleanly behind the high feed and strike a sharp, controlled drop shot.",
                "Follow the drop forward in one fluid stride toward the service line instead of retreating.",
                "Intercept the short return early above tape height with a crisp net kill or tight spin.",
                "Reset to mid-court base before the next pair begins.",
            ),
            coachingCue = "Follow every quality drop forward to take time away from the opponent at the net.",
        ),
        Exercise(
            id = "forecourt-lunge-net-kill",
            title = "Forecourt Lunge & Net-Kill Feed",
            category = ExerciseCategory.ON_COURT,
            focus = FocusArea.COURT_COVERAGE,
            prescription = "3 x 14 shuttles",
            setup = "Feeder stands close to the opposite net with rapid short feeds to both forecourt corners.",
            steps = listOf(
                "Start just behind the short service line with the racket head held high in front.",
                "Push forward into a balanced heel-first lunge to meet the shuttle early near tape level.",
                "Play either a tight tumbling net shot or a compact net tap, keeping the back foot anchored for balance.",
                "Rebound cleanly behind the service line before the next feed.",
            ),
            coachingCue = "Lead with the racket high and land heel-first to keep the forecourt lunge stable.",
        ),
        Exercise(
            id = "repeat-burst-multi-shuttle",
            title = "Repeat-Burst Multi-Shuttle",
            category = ExerciseCategory.ON_COURT,
            focus = FocusArea.EXPLOSIVE_SPEED,
            prescription = "6 x 10 shuttles · 30 s rest",
            setup = "Feeder with 10 shuttles per set feeding rapidly to random corners at near-maximum pace.",
            steps = listOf(
                "Explode out of a sharp split-step to reach each feed as early as possible.",
                "Prioritize maximum first-step acceleration out of base across all 10 shuttles in the set.",
                "Take a full 30-second walk recovery between sets so burst speed stays high on sets five and six.",
            ),
            coachingCue = "Keep the first step out of the split-step explosive from the first shuttle to the last.",
        ),
        Exercise(
            id = "figure-8-chasse-shadow",
            title = "Figure-8 Chasse Flow",
            category = ExerciseCategory.ON_COURT,
            focus = FocusArea.COURT_COVERAGE,
            prescription = "4 x 40 s · 20 s rest",
            setup = "Solo on half court. Move continuously through diagonal corners in a smooth figure-8 loop.",
            steps = listOf(
                "Lunge to the front forehand corner, then push back diagonally through centre base to the rear backhand corner.",
                "Play an around-the-head shadow clear and drive forward diagonally to the front backhand corner.",
                "Push back diagonally to the rear forehand corner, finishing each cycle cleanly through mid-court.",
                "Reverse the figure-8 direction on sets three and four.",
            ),
            coachingCue = "Keep steps quiet and economical - one chasse and one lunge per corner instead of extra shuffling.",
        ),
        Exercise(
            id = "china-jump-sideline-intercept",
            title = "China-Jump Sideline Interception",
            category = ExerciseCategory.ON_COURT,
            focus = FocusArea.EXPLOSIVE_SPEED,
            prescription = "4 x 12 shuttles",
            setup = "Feeder hits brisk flat pushes toward the mid-court forehand sideline from across the net.",
            steps = listOf(
                "Hold an active mid-court ready stance with racket up above waist height.",
                "Push off both feet laterally into a low, explosive China jump toward the forehand sideline.",
                "Intercept the shuttle early in mid-air with a crisp stick smash or fast downward drive.",
                "Land smoothly on the outer foot and rebound back across the centre line immediately.",
            ),
            coachingCue = "Cut off the shuttle at peak height before it drifts behind the shoulder line.",
        ),
        Exercise(
            id = "star-4-corner-split-timing",
            title = "4-Corner Reactive Split-Step",
            category = ExerciseCategory.ON_COURT,
            focus = FocusArea.BASE_RECOVERY,
            prescription = "4 x 12 cues · 25 s rest",
            setup = "Partner stands across the net pointing or tossing a shuttle to one of the four outer corners on cue.",
            steps = listOf(
                "Bounce lightly on the mid-court base and time a crisp split-step landing as the partner reveals the corner.",
                "Explode out of the loaded split-step directly to the called corner with a clean shadow stroke.",
                "Recover immediately to base and reset posture before the partner initiates the next cue.",
            ),
            coachingCue = "Land the split-step right as the opponent strikes so the legs are already loaded to push off.",
        ),

        // --- Attack & Rear-Court Weapons (5 on-court drills) ---
        Exercise(
            id = "around-the-head-scissor-kick",
            title = "Around-the-Head Scissor Kick",
            category = ExerciseCategory.ON_COURT,
            focus = FocusArea.ATTACK,
            prescription = "4 x 12 shuttles",
            setup = "Feeder lifts consistently deep into the rear overhead backhand corner.",
            steps = listOf(
                "Chasse sideways behind the shuttle early so the non-racket shoulder points toward the net.",
                "Load onto the rear foot, spring upward, and execute a clean scissor-kick rotation through contact.",
                "Drive a steep straight drop, fast clear, or cross smash from above the non-racket shoulder.",
                "Use the front-to-back leg swap to land moving straight forward toward mid-court base.",
            ),
            coachingCue = "Get behind the shuttle first so the scissor kick carries momentum forward instead of falling backward.",
        ),
        Exercise(
            id = "jump-smash-and-follow",
            title = "Jump Smash & Net Follow",
            category = ExerciseCategory.ON_COURT,
            focus = FocusArea.ATTACK,
            prescription = "4 x 10 pairs",
            setup = "Feeder delivers a mid-high lift to the rear forehand followed by a soft mid-forecourt defensive block.",
            steps = listOf(
                "Move behind the lift, gather both feet symmetrically, and spring into a compact tuck jump.",
                "Snap the torso and forearm through a steep downward smash aimed toward the sideline.",
                "Land softly with weight forward and sprint straight in to tap away the loose net block.",
                "Reset smoothly to mid-court base before the next pair.",
            ),
            coachingCue = "Land balanced with the chest moving forward so the follow-up step to the net is automatic.",
        ),
        Exercise(
            id = "rear-corner-slice-drop-variation",
            title = "Disguised Slice-Drop & Clear",
            category = ExerciseCategory.ON_COURT,
            focus = FocusArea.ATTACK,
            prescription = "3 x 16 shuttles",
            setup = "Feeder sends alternating high clears to both rear corners; place flat targets near the front sidelines.",
            steps = listOf(
                "Prepare every rear-court stroke with the exact same high elbow and non-racket arm pointing up.",
                "Alternate steep cross-court slice drops toward the front targets with flat attacking clears to the baseline.",
                "Keep swing speed crisp right up to contact so the drop and clear look identical early.",
                "Recover cleanly to mid-court base after every shot.",
            ),
            coachingCue = "Keep the backswing identical on drops and clears - change only the racket-face angle at impact.",
        ),
        Exercise(
            id = "stick-smash-half-court-pressure",
            title = "Half-Court Stick-Smash Pressure",
            category = ExerciseCategory.ON_COURT,
            focus = FocusArea.ATTACK,
            prescription = "4 x 12 shuttles",
            setup = "Feeder plays repeated medium-height defensive lifts down one half-court channel.",
            steps = listOf(
                "Stay balanced inside the rear mid-court without retreating all the way to the baseline.",
                "Hit crisp, steep stick smashes using short forearm pronation and finger squeeze instead of a huge wind-up.",
                "Step slightly forward after each smash to keep downward angle and rush the defender.",
                "Switch channels after two sets.",
            ),
            coachingCue = "Prioritize steep angle and quick recovery over swinging at 100% power.",
        ),
        Exercise(
            id = "backhand-rear-corner-clear",
            title = "Backhand Rear-Corner Escape",
            category = ExerciseCategory.ON_COURT,
            focus = FocusArea.ATTACK,
            prescription = "3 x 12 shuttles",
            setup = "Feeder sends wide deep lifts past the rear backhand corner to simulate late recovery.",
            steps = listOf(
                "Pivot smoothly into the corner with the racket-side foot planting last and the back angled toward the net.",
                "Shift cleanly to a bevel or thumb grip with the elbow leading upward toward the shuttle.",
                "Whip the forearm and fingers cleanly to send a high straight clear or tight straight drop.",
                "Push off the planted foot immediately to spin back facing the net on mid-court base.",
            ),
            coachingCue = "Lead with the elbow high and finish with a crisp forearm snap rather than swinging across the body.",
        ),

        // --- Defence & Flat Mid-Court Exchanges (5 on-court drills) ---
        Exercise(
            id = "low-smash-block-and-lift",
            title = "Smash Block & Deep Lift Defence",
            category = ExerciseCategory.ON_COURT,
            focus = FocusArea.DEFENCE,
            prescription = "4 x 15 shuttles",
            setup = "Partner or feeder smashes consistently from mid-rear court toward both sides of the defender.",
            steps = listOf(
                "Hold a wide, low defensive stance slightly behind mid-court with the racket head held out in front.",
                "Absorb steep smashes with soft fingers to drop tight blocks just over the tape.",
                "Every third shuttle, accelerate the forearm crisply to drive a deep high lift all the way to the back tramlines.",
                "Reset low and square after every contact without standing upright prematurely.",
            ),
            coachingCue = "Keep the racket out in front of the knees so contact happens ahead of the body line.",
        ),
        Exercise(
            id = "flat-drive-channel-exchange",
            title = "Mid-Court Flat Drive Exchange",
            category = ExerciseCategory.ON_COURT,
            focus = FocusArea.DEFENCE,
            prescription = "4 x 45 s · 20 s rest",
            setup = "Two players stand in mid-court on the same half-court channel exchanging continuous flat drives.",
            steps = listOf(
                "Keep the racket head up at chest height with a loose thumb-ready grip for fast grip changes.",
                "Punch flat drives back using short, compact swings from the forearm and fingers.",
                "Step forward slightly whenever a reply rises to keep the opponent pinned back.",
                "Switch channels and repeat on the opposite side.",
            ),
            coachingCue = "Keep the backswing tiny and rebound the racket back to the centre of the chest immediately.",
        ),
        Exercise(
            id = "defensive-cross-block-recovery",
            title = "Body-Smash Deflect & Cross Block",
            category = ExerciseCategory.ON_COURT,
            focus = FocusArea.DEFENCE,
            prescription = "3 x 14 shuttles",
            setup = "Feeder drives hard body smashes toward the defender's racket hip and chest.",
            steps = listOf(
                "Bias the grip slightly toward backhand in the defensive ready stance to protect the body zone.",
                "Create a small pocket of space by dropping the hip slightly rather than swinging wide.",
                "Guide the shuttle softly into the opposite cross-court forecourt space to break the attacker's rhythm.",
                "Step in calmly behind the cross block ready to cover the tight net reply.",
            ),
            coachingCue = "Stay compact with elbows in front of the torso - let the shuttle's pace do the work on the block.",
        ),
        Exercise(
            id = "wide-forehand-dive-save",
            title = "Wide Sideline Reach & Recovery",
            category = ExerciseCategory.ON_COURT,
            focus = FocusArea.DEFENCE,
            prescription = "3 x 12 shuttles",
            setup = "Feeder drives wide attacking shots toward the outer singles sidelines just below net height.",
            steps = listOf(
                "Split-step in mid-court and drive out into a long, low lateral lunge toward the sideline.",
                "Extend the racket arm cleanly with the non-racket arm reaching back for counterbalance.",
                "Lift the shuttle high and deep to buy recovery time, keeping the racket head slightly above the wrist.",
                "Push powerfully off the outside edge of the lunge foot back to the centre line.",
            ),
            coachingCue = "Buy time with height when stretched wide instead of forcing a risky flat reply.",
        ),
        Exercise(
            id = "half-court-consistency-20",
            title = "20-Shot High-Margin Rally",
            category = ExerciseCategory.ON_COURT,
            focus = FocusArea.DEFENCE,
            prescription = "4 successful 20-shot rallies",
            setup = "Half-court singles rally using clears, drops, lifts, and net shots with no full smashes.",
            steps = listOf(
                "Build a controlled rally down one half court aiming for 20 consecutive shots without an unforced error.",
                "Keep clears deep inside the rear court and drops crisp with safe margin above the net tape.",
                "Recover to mid-court base after every single shot, maintaining calm footwork tempo.",
                "If an error occurs before 20, restart the count and note which shot broke down.",
            ),
            coachingCue = "Aim 30 cm inside the lines and prioritize balance before speed on every shot.",
        ),

        // --- Net Play, Serve & 3rd-Shot Control (4 on-court drills) ---
        Exercise(
            id = "tumbling-net-spin-duel",
            title = "Tumbling Net-Spin Control",
            category = ExerciseCategory.ON_COURT,
            focus = FocusArea.NET_PLAY,
            prescription = "4 x 45 s · 15 s rest",
            setup = "Both players stay inside the forecourt trading tight spinning net shots across the full net width.",
            steps = listOf(
                "Enter each shot early with the racket extended high near tape height and fingers relaxed.",
                "Brush cleanly under the cork from outside to inside to create tight forward tumble over the tape.",
                "Step back one small recovery pace toward the short service line after each shot.",
                "Keep every reply tight enough that the opponent must lift or strike below tape height.",
            ),
            coachingCue = "Guide the shuttle with relaxed fingertips and a high contact point - never scoop upward from low.",
        ),
        Exercise(
            id = "cross-net-and-hold-deception",
            title = "Hold-and-Flick Cross-Net",
            category = ExerciseCategory.ON_COURT,
            focus = FocusArea.NET_PLAY,
            prescription = "3 x 14 shuttles",
            setup = "Feeder tosses gentle net feeds alternately to the forehand and backhand forecourt corners.",
            steps = listOf(
                "Lunge early to the forecourt with the racket head presented flat and quiet beside the shuttle.",
                "Add a tiny split-second hold at contact to freeze the opponent before playing straight or cross-net.",
                "Use a compact wrist-and-finger flick to send the cross-net tight past the opposite sideline.",
                "Rebound cleanly behind the service line immediately after the touch.",
            ),
            coachingCue = "Arrive early with a still racket head - deception comes from early balance, not late swinging.",
        ),
        Exercise(
            id = "low-flick-serve-third-shot",
            title = "Low & Flick Serve + 3rd Shot",
            category = ExerciseCategory.ON_COURT,
            focus = FocusArea.SERVE_RETURN,
            prescription = "4 x 12 serves (6 even / 6 odd)",
            setup = "Practice from both right and left service boxes with a partner returning low pushes and lifts.",
            steps = listOf(
                "Use the same calm backhand serve routine from right behind the service line on every point.",
                "Mix tight low serves skimming the tape toward the T with sudden disguised flick serves to the back box.",
                "Immediately raise the racket after impact and step into a ready stance to attack the third shot.",
                "Track accuracy separately from even and odd boxes to remove side-to-side drop-off.",
            ),
            coachingCue = "Keep the backswing identical on low and flick serves and get the racket straight up for the third shot.",
        ),
        Exercise(
            id = "serve-receive-rush-and-push",
            title = "Service Return Rush & Push",
            category = ExerciseCategory.ON_COURT,
            focus = FocusArea.SERVE_RETURN,
            prescription = "4 x 12 receives (6 even / 6 odd)",
            setup = "Server mixes tight short serves and occasional flick serves; receiver starts poised behind the service line.",
            steps = listOf(
                "Set a balanced athletic receive stance with weight on the balls of the feet and racket head held above tape height.",
                "Read the low serve early and take one sharp forward step to push flat into the mid-court sidelines or tap loose serves.",
                "Stay poised through the back heel so a sudden flick serve can still be chased with one quick chasse.",
                "Alternate even and odd service courts every six receives.",
            ),
            coachingCue = "Hold the racket high in front and attack flat into open space without over-committing past the flick.",
        ),

        // --- Off-Court Strength, Power, Endurance & Prehab (10 off-court exercises) ---
        Exercise(
            id = "split-squat-endurance",
            title = "Split-Squat Endurance Sets",
            category = ExerciseCategory.OFF_COURT,
            focus = FocusArea.ENDURANCE,
            prescription = "3 x 12 / leg · 45 s rest",
            setup = "Off court with bodyweight or light dumbbells. Use flat stable shoes on a non-slip floor.",
            steps = listOf(
                "Take a long split stance with the torso upright and weight centered over the front mid-foot.",
                "Lower the back knee steadily under control over two seconds until the front thigh nears parallel.",
                "Drive smoothly back up through the front foot without locking the knee at the top.",
                "Complete all 12 reps on one side, then switch immediately to the other leg.",
            ),
            coachingCue = "Keep the front knee tracking over the toes and avoid rounding the lower back as fatigue builds.",
        ),
        Exercise(
            id = "lateral-bounds-split-jumps",
            title = "Lateral Bounds & Split-Jumps",
            category = ExerciseCategory.OFF_COURT,
            focus = FocusArea.EXPLOSIVE_SPEED,
            prescription = "4 x 8 bounds + 6 split-jumps",
            setup = "Off court on a sprung hall floor or gym mat with 3 metres of lateral space.",
            steps = listOf(
                "Push explosively off the outside foot to bound laterally, sticking the landing on the opposite leg for one beat.",
                "Complete 8 alternating lateral bounds, staying low through the hips on each landing.",
                "Transition straight into 6 quick-contact plyometric split-jumps, landing softly in a wide ready stance.",
                "Rest 60 seconds between rounds to preserve elastic power quality.",
            ),
            coachingCue = "Land quietly through the ball of the foot and rebound quickly without letting the knees collapse inward.",
        ),
        Exercise(
            id = "lunge-deceleration-pushback",
            title = "Drop-Lunge Brake & Push-Back",
            category = ExerciseCategory.OFF_COURT,
            focus = FocusArea.STRENGTH_MOBILITY,
            prescription = "3 x 10 / leg · 45 s rest",
            setup = "Off court with bodyweight on a non-slip floor; enough room for a full badminton lunge stride.",
            steps = listOf(
                "Stand tall, then drive briskly forward into a deep badminton lunge landing heel-to-midfoot.",
                "Brake cleanly at the bottom for one full second with the torso tall and front knee stacked over the ankle.",
                "Push powerfully back off the lead foot in one crisp drive all the way to the starting stance.",
                "Complete all reps on the dominant lunge leg, then match every rep on the opposite leg.",
            ),
            coachingCue = "Absorb the lunge silently with an upright chest before snapping straight back to standing.",
        ),
        Exercise(
            id = "pogo-split-step-hops",
            title = "Quick-Contact Pogo & Split Hops",
            category = ExerciseCategory.OFF_COURT,
            focus = FocusArea.EXPLOSIVE_SPEED,
            prescription = "4 x 12 pogos + 6 split landings",
            setup = "Off court on a sprung hall floor or firm mat wearing court shoes.",
            steps = listOf(
                "Perform 12 rapid vertical pogo hops keeping the knees softly braced and bouncing off the balls of the feet.",
                "Minimize ground contact time so each bounce feels crisp and springy like a jump rope.",
                "Immediately follow with 6 snappy hops landing wide into a low athletic badminton split-step.",
                "Take 45 seconds of full rest between sets to keep every contact elastic.",
            ),
            coachingCue = "Stay tall and springy on the forefeet - spend as little time on the floor as possible.",
        ),
        Exercise(
            id = "single-leg-rdl-balance",
            title = "Single-Leg RDL & Hip Balance",
            category = ExerciseCategory.OFF_COURT,
            focus = FocusArea.STRENGTH_MOBILITY,
            prescription = "3 x 10 / leg · 40 s rest",
            setup = "Off court barefoot or in flat shoes, bodyweight or holding one light dumbbell.",
            steps = listOf(
                "Balance on one slightly softened knee with the hips square to the floor.",
                "Hinge smoothly forward at the hips while extending the free leg straight back until the torso nears horizontal.",
                "Hold steady for one beat without letting the raised hip open upward.",
                "Drive the hips back through cleanly to tall standing and switch sides after 10 reps.",
            ),
            coachingCue = "Keep both hip bones level to the floor and lengthen from head to back heel.",
        ),
        Exercise(
            id = "copenhagen-adductor-hold",
            title = "Lateral Side-Lunge Mobility",
            category = ExerciseCategory.OFF_COURT,
            focus = FocusArea.STRENGTH_MOBILITY,
            prescription = "3 x 10 / side · 40 s rest",
            setup = "Off court on a clear floor space; bodyweight or holding a light weight at chest height.",
            steps = listOf(
                "Step into a wide stance with both feet pointing slightly outward and the chest tall.",
                "Shift weight smoothly over one bent knee while keeping the opposite leg straight and inner thigh lengthened.",
                "Pause for one controlled beat at the bottom with the heel flat on the floor.",
                "Drive strongly through the loaded foot back to centre, then alternate to the other side.",
            ),
            coachingCue = "Sit the hips back and keep the loaded heel planted flat rather than rolling onto the toes.",
        ),
        Exercise(
            id = "rotational-core-chop",
            title = "Rotational Core Whip",
            category = ExerciseCategory.OFF_COURT,
            focus = FocusArea.STRENGTH_MOBILITY,
            prescription = "3 x 10 / side · 45 s rest",
            setup = "Off court with a light resistance band anchored at shoulder height or a 2 to 3 kg medicine ball.",
            steps = listOf(
                "Stand side-on in an athletic half-squat holding the band or ball across the back hip.",
                "Rotate briskly from the hips and ribcage to drive the hands diagonally across the torso.",
                "Brake smoothly at the finish without letting the lower back arch or the front knee cave inward.",
                "Return slowly under control to the loaded coil and finish both sides evenly.",
            ),
            coachingCue = "Initiate the whip from hip and trunk rotation instead of pulling only with the arms.",
        ),
        Exercise(
            id = "plank-shoulder-taps",
            title = "Anti-Rotation Plank Shoulder Taps",
            category = ExerciseCategory.OFF_COURT,
            focus = FocusArea.ENDURANCE,
            prescription = "3 x 20 slow taps · 35 s rest",
            setup = "Off court on a gym mat with feet set slightly wider than hip width for stability.",
            steps = listOf(
                "Hold a crisp straight-line high plank with hands directly under the shoulders and glutes braced.",
                "Lift one hand slowly to tap the opposite shoulder without letting the hips twist or sag.",
                "Place the hand down softly and repeat on the other side with steady breathing.",
                "Stop the set early if the pelvis starts rocking from side to side.",
            ),
            coachingCue = "Imagine balancing a shuttle on the lower back - keep the hips completely still on every tap.",
        ),
        Exercise(
            id = "eccentric-calf-ankle-control",
            title = "Single-Leg Calf Raise & Slow Lower",
            category = ExerciseCategory.OFF_COURT,
            focus = FocusArea.STRENGTH_MOBILITY,
            prescription = "3 x 12 / leg · 35 s rest",
            setup = "Off court beside a wall or sturdy step for light fingertip balance.",
            steps = listOf(
                "Stand tall on one foot with the knee straight and core lightly braced.",
                "Press smoothly all the way up onto the ball of the foot over one second, keeping pressure over the big toe.",
                "Lower the heel steadily over three full seconds until touching the floor or slightly below step height.",
                "Complete 12 controlled reps per leg to strengthen the Achilles tendon for split-step landings.",
            ),
            coachingCue = "Rise straight over the big toe and make the three-second lowering phase smooth and unhurried.",
        ),
        Exercise(
            id = "shoulder-external-rotation-prehab",
            title = "Overhead Shoulder External Rotation",
            category = ExerciseCategory.OFF_COURT,
            focus = FocusArea.STRENGTH_MOBILITY,
            prescription = "3 x 15 reps · 30 s rest",
            setup = "Off court with a light resistance band anchored at waist or chest height.",
            steps = listOf(
                "Stand tall with shoulder blades gently set back and elbows bent to 90 degrees at shoulder height.",
                "Rotate the forearms smoothly upward and back against light band tension without shrugging the shoulders.",
                "Hold the open position for one beat to engage the posterior rotator cuff.",
                "Lower the forearms slowly back to horizontal over two seconds.",
            ),
            coachingCue = "Keep the ribs down and shoulder blades steady - use light resistance with strict control.",
        ),
    )

    private val index: Map<String, Exercise> = all.associateBy { it.id }

    fun byId(id: String): Exercise = requireNotNull(index[id]) { "Unknown exercise id: $id" }

    /** Ordered library filter pills exposed as a plain list for Swift and Compose. */
    val allFilters: List<DrillLibraryFilter> = DrillLibraryFilter.entries

    /** Returns every catalog exercise matching [filter] in catalog order. */
    fun filtered(filter: DrillLibraryFilter): List<Exercise> = when (filter) {
        DrillLibraryFilter.ALL -> all
        DrillLibraryFilter.FOOTWORK -> all.filter {
            it.category == ExerciseCategory.ON_COURT && it.focus in FOOTWORK_FOCUSES
        }
        DrillLibraryFilter.ATTACK -> all.filter {
            it.category == ExerciseCategory.ON_COURT && it.focus == FocusArea.ATTACK
        }
        DrillLibraryFilter.DEFENCE -> all.filter {
            it.category == ExerciseCategory.ON_COURT && it.focus == FocusArea.DEFENCE
        }
        DrillLibraryFilter.NET_AND_SERVE -> all.filter {
            it.category == ExerciseCategory.ON_COURT &&
                (it.focus == FocusArea.NET_PLAY || it.focus == FocusArea.SERVE_RETURN)
        }
        DrillLibraryFilter.OFF_COURT -> all.filter { it.category == ExerciseCategory.OFF_COURT }
    }

    private val FOOTWORK_FOCUSES = setOf(
        FocusArea.BASE_RECOVERY,
        FocusArea.COURT_COVERAGE,
        FocusArea.LATERAL_BALANCE,
        FocusArea.ENDURANCE,
        FocusArea.EXPLOSIVE_SPEED,
    )
}
