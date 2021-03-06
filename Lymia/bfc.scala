/* 
	Brainfuck Compiler
	Copyright (C) 2011 Lymia <lymiahugs@gmail.com>
	
	This program is free software: you can redistribute it and/or modify
	it under the terms of the GNU General Public License as published by
	the Free Software Foundation, either version 3 of the License, or
	(at your option) any later version.
	
	This program is distributed in the hope that it will be useful,
	but WITHOUT ANY WARRANTY; without even the implied warranty of
	MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
	GNU General Public License for more details.
	
	You should have received a copy of the GNU General Public License
	along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

package lymia.bfc

import java.util.Stack
import java.io._
import scala.collection.immutable._
import scala.collection.mutable.ListBuffer

//TODO: Figure out how to detect clears

object Main {
	def main(args:Array[String]) = try {
		args match {
			case Array(source,target) => {
				val s = new File(source)
				val t = new File(target)
				if(!s.exists) throw GeneralError("source does not exist")
				writeFile(t, BrainfuckCompiler.compile(new String(readFile(s))))
			}
			case _ => println("Usage: [source file] [target]")
		}
	} catch {
		case GeneralError(x) => println("general error: "+x)
	}
	private def readFile(f: File): Array[Byte] = {
		val data = new Array[Byte](f.length().asInstanceOf[Int])
		val in = new DataInputStream(new FileInputStream(f))
		in.readFully(data)
		in.close()
		return data
	}
	private def writeFile(f: File, data: String) {
		val out = new FileOutputStream(f)
		out.write(data.getBytes())
		out.close()
	}
}

object BrainfuckCompiler {
	def compile(source: String): String = {
		println("Parsing...")
		val parsed = parse(source)

		println("Optimizing...")
		val optimized = optimize(parsed)

		println("Writing code header...")
		val out = new ByteArrayOutputStream()
		val print = new PrintStream(out)
		print.println("/* Code generated by Lymia's Brainfuck Compiler */")
		print.println("")
		print.println("/*")
		print.println("Original source:")
		print.println("")
		
		var temp = filter(source)
		while(temp.length>80) {
			print.println(temp.substring(0,80))
			temp = temp.substring(80)
		}
		print.println(temp)

		print.println("*/")
		print.println("")

		println("Writing code body...")
		generate(print,optimized)

		println("Done!")

		return new String(out.toByteArray)
	}

	def filter(source: String): String = {
		val rawChars = (0 to source.length-1) map {source.charAt(_)}
		val chars = rawChars.filter(s => s=='+' || s=='-' || s=='<' || s=='>' || s=='.' || s==',' || s=='[' || s==']')
		return new String(chars.toArray)
	}
	def parse(source: String): Seq[Instruction] = {
		val rawChars = (0 to source.length-1) map {source.charAt(_)}
		val chars = rawChars.filter(s => s=='+' || s=='-' || s=='<' || s=='>' || s=='.' || s==',' || s=='[' || s==']')

		val stack = new Stack[ListBuffer[Instruction]]
		var list = ListBuffer[Instruction]()
		chars foreach {
			_ match {
				case '+' => list append AddMem(0,1)
				case '-' => list append AddMem(0,-1)
				case '>' => list append AddPtr(1)
				case '<' => list append AddPtr(-1)
				case '[' => {
					stack.push(list)
					list = ListBuffer[Instruction]()
				}
				case ']' => {
					val ins = Loop(list.toList)
					list = stack.pop()
					list append ins
				}
				case '.' => list append Output(0)
				case ',' => list append Input(0)
			}
		}

		return list.toList
	}
	
	def optimize(source: Seq[Instruction]): Seq[Instruction] = removeZero(combineOpers(source))
	
	def combineOpers(source: Seq[Instruction]): Seq[Instruction] = {
		import scala.collection.mutable.HashMap
		
		val list = ListBuffer[Instruction]()
		var memp = 0
		val changeMap: HashMap[Int,Int] = new HashMap[Int,Int]
		
		def get(m:Int) = changeMap get m match {
			case None => 0
			case Some(x) => x
		}
		def set(m:Int, v:Int) = changeMap.put(m,v)
		def add(m:Int, v:Int) = set(m,get(m)+v)
		def commit = {
			changeMap.toList foreach (x => list append AddMem(x._1,x._2))
			list append AddPtr(memp)
			changeMap.clear
			memp = 0
		}
		
		source foreach {_ match {
			case AddMem(o,x) => add(memp+o,x)
			case AddPtr(x) => memp = memp+x
			case Loop(x) => {
				commit
				list append Loop(combineOpers(x))
			}
			case Input(x) => {
				val addr = memp+x
				set(addr,0)
				list append Input(addr)
			}
			case Output(x) => {
				val addr = memp+x
				list append AddMem(addr,get(addr))
				set(addr,0)
				list append Output(addr)
			}
			case Clear(x) => {
				val addr = memp+x
				set(addr,0)
				list append Clear(addr)
			}
			case x => {
				commit
				list append x
			}
		}}
		commit
		list.toList
	}
	def removeZero(source: Seq[Instruction]): Seq[Instruction] = {
		val filtered = source filter {
			_ match {
				case AddMem(_,0) => false
				case AddPtr(0) => false
				case _ => true
			}
		}
		filtered map {
			_ match {
				case Loop(x) => Loop(removeZero(x))
				case x => x
			}
		}
	}

	def generate(out: PrintStream, source: Seq[Instruction]) {
		out.println("#include <stdio.h>")

		out.println("unsigned char memory[1024*64];") //64 KB memory
		out.println("unsigned char* ptr = memory;")
		out.println("int main() {")
		def indent(i:Int) = new String(Array.fill[Char](i)('\t'))
		def generateRecurse(i: Int, source: Seq[Instruction]): Unit = source foreach {
			_ match {
				case AddMem(offset,x) => out.println(indent(i)+"*(ptr+"+offset+")+=("+x+");")
				case AddPtr(x) => out.println(indent(i)+"ptr+=("+x+");")
				case Loop(x) => {
					out.println(indent(i)+"while(*ptr){")
					generateRecurse(i+1,x)
					out.println(indent(i)+"}")
				}
				case Input(offset) => out.println(indent(i)+"*(ptr+"+offset+")=getchar();")
				case Output(offset) => out.println(indent(i)+"putchar(*(ptr+"+offset+"));")
				case Clear(offset) => out.println(indent(i)+"*(ptr+"+offset+")=0;")
				case _ => out.println(indent(i)+"printf(\"internal error: invalid command\");return 1;")
			}
		}
		generateRecurse(1,source)
		out.println("\treturn 0;")
		out.println("}")
	}
}

sealed class Instruction
case class AddMem(offset: Int, amount: Int) extends Instruction
case class AddPtr(amount: Int) extends Instruction
case class Loop(code: Seq[Instruction]) extends Instruction
case class Input(offset: Int) extends Instruction
case class Output(offset: Int) extends Instruction
case class Clear(offset: Int) extends Instruction

case class GeneralError(value: String) extends Exception
