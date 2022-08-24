/** IO bundle definition for an Arbiter, which takes some number of ready-valid inputs and outputs
 * (selects) at most one.
 * @groupdesc Signals The actual hardware fields of the Bundle
 *
 * @param gen data type
 * @param n number of inputs
 */
class ArbiterIOVar[T <: Data](private val gen: T, val n: Int) extends Bundle {
  // See github.com/freechipsproject/chisel3/issues/765 for why gen is a private val and proposed replacement APIs.

  /** Input data, one per potential sender
   *
   * @group Signals
   */
  val in = Flipped(Vec(n, Decoupled(gen)))

  /** Output data after arbitration
   *
   * @group Signals
   */
  val out = Decoupled(gen)

  /** One-Hot vector indicating which output was chosen
   *
   * @group Signals
   */
  val chosen = Output(UInt(log2Ceil(n).W))
  //edited
  val rob_head = Input(UInt(32.W))
}




/**
 * Arbiter Control determining which producer has access
 */
/**
private object ArbiterCtrlVarTime {
  def apply(request: Vec(n, Decoupled(ExeUnitResp))): Seq[Bool] = {
    request.map(_.valid).length match{
      case 0 => Seq()
      case 1 => Seq(true.B)
      case _ => varTime(request)
    }


    /** true.B +: request.tail.init.scanLeft(request.head)(_ || _).map(!_) */
  }

/**
  def varTime(request: Vec(n, Decoupled(ExeUnitResp))): Seq[Bool] = {
    //val newReq: Seq[Bool] = Seq.empty[bool]
    val oldest = Seq()
    oldest :+ request(request.length-1)
    //find oldest in request
    for (i <- 0 to request.length-1 by +1) {
      if (IsOlder(request(i).uop.rob_idx, oldest(0).uop.rob_idx, io.rob_head)){
        oldest +: io.in.slice(i,i)
      }
    }
    //after loop oldest is oldest(0)
    return oldest
  }
  */
}
*/

/**
 *Arbiter with variable time execution
 *edited default case in Arbiterctl
 */
class ArbiterVarTime[T <: Data](val gen: ExeUnitResp, val n: Int) extends Module {
  val io = IO(new ArbiterIOVar(gen, n))

  io.chosen := (n - 1).asUInt
  io.out.bits := io.in(n - 1).bits
  for (i <- n - 2 to 0 by -1) {
    when(io.in(i).valid) {
      io.chosen := i.asUInt
      io.out.bits := io.in(i).bits
    }
  }
  val uint = 0.U
  val returngrant = VecInit(Seq.fill(n){false.B})

  val oldest = Reg(UInt())
  oldest := PriorityEncoder(io.in.map(_.valid))
  //val i = 0
  //val oldest = io.in.length
  //val valids = io.in.map(_.valid)
  //val oldest = valids.indexWhere(x => {x==true.B})
  //find oldest valid input signal
  //val oldest = PriorityEncoder(io.in.map(_.valid))

  //if(oldest == -1){
  //  oldest = valids.indexWhere(x => {x==true.B})
  //}
  //while (i < to io.in.length && oldest == io.in.length)
  /**for (i <- 0 to io.in.length && oldest == io.in.length){
    if(io.in(i).valid){
      oldest = i
    }
    i=i+1
  }
*/

  //val returnseq = Seq.fill(n)(false.B)
  //val returnseq: Seq[Bool] = Seq.empty[Bool]
  //val oldest = 0.U
  //val oldest: Seq[ExeUnitResp] = Seq.empty[ExeUnitResp]
  //oldest :+ io.in(io.in.length-1)
  //find oldest in request
  //val oldestValid: Seq[UInt] = Seq.empty[UInt]
  //oldestValid :+ oldest
  //when(oldest <= i){
  for(i <- 0 to n-1 by +1) {
    when(io.in(i).valid){
      //when(IsOlder(io.in(i).bits.uop.rob_idx, io.in(oldestValid.last).bits.uop.rob_idx, io.rob_head)){
      when(IsOlder(io.in(i).bits.uop.rob_idx, io.in(oldest).bits.uop.rob_idx, io.rob_head)){
        oldest := i.asUInt
      }
    }
    for (j <- 0 to n-1 by +1){
      if(j.asUInt==oldest){
        //assert(returngrant(j) === true.B)
        returngrant(j) := true.B
      }else{
        //assert(returngrant(j) === false.B)
        returngrant(j) := false.B
      }
    }
    //for (i <- oldest+1 until io.in.length by +1) {
    //for (i <- oldest+1.U until n by +1) {
      //oldestValid :+ Mux(IsOlder(io.in(i).bits.uop.rob_idx, io.in(oldest).bits.uop.rob_idx, io.rob_head), i, oldest)

  }
  //else{
      //  oldestValid :+ oldest
      //}

  //val returnseq: Seq[Bool] = Seq.empty[Bool]
  //val returnseq = Seq.fill(io.in.length){false.B}
  //val returnseq: Seq[Bool] = Seq.fill(io.in.length)(false.B)
  //def returnseq: Seq[Bool]
  //returnseq.updated(oldest, true.B)
  //returnseq(oldest) = true.B
  //val returnseq: Seq[Bool] = Seq.fill(io.in.length)(false.B).updated(oldest, true.B)
  /**for(i <- 0 to n-1 by +1){
    if(i.U == oldest){
      returnseq :+ true.B
    }
    else{
      returnseq :+ false.B
    }
  }*/

  /**for(i <- 0 to n-1 by +1){
    i.U match {
      case oldest => returnseq :+ true.B
      case _ => returnseq :+ false.B
    }
  }*/



  val arbiterCtl = io.in.map(_.valid).length match{
    case 0 => Seq()
    case 1 => Seq(true.B)
    case _ => returngrant //returnseq
  }


  val grant = arbiterCtl
  for ((in, g) <- io.in.zip(grant))
    in.ready := g && io.out.ready
  io.out.valid := !grant.last || io.in.last.valid
}
