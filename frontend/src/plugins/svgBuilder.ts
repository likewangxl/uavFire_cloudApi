import { readFileSync, readdirSync } from 'fs'

let idPerfix = ''
const svgTitle = /<svg([^>+].*?)>/
const clearHeightWidth = /(width|height)="([^>+].*?)"/g
const hasViewBox = /(viewBox="[^>+].*?")/g
const clearReturn = /(\r)|(\n)/g

// Find the svg file
function svgFind(e: string): string[] {
  const arr: string[] = []
  const dirents = readdirSync(e, { withFileTypes: true })
  for (const dirent of dirents) {
    if (dirent.isDirectory()) arr.push(...svgFind(e + dirent.name + '/'))
    else {
      const svg = readFileSync(e + dirent.name)
        .toString()
        .replace(clearReturn, '')
        .replace(svgTitle, ($1: string, $2: string) => {
          let width = 0
          let height = 0
          let content = $2.replace(clearHeightWidth, (s1: string, s2: string, s3: string) => {
            if (s2 === 'width') width = parseFloat(s3) || 0
            else if (s2 === 'height') height = parseFloat(s3) || 0
            return ''
          })
          if (!hasViewBox.test($2)) content += `viewBox="0 0 ${width} ${height}"`
          return `<symbol id="${idPerfix}-${dirent.name.replace('.svg', '')}" ${content}>`
        }).replace('</svg>', '</symbol>')
      arr.push(svg)
    }
  }
  return arr
}

export const svgBuilder = (path: string, perfix = 'icon') => {
  if (path === '') return
  idPerfix = perfix
  const res = svgFind(path)
  console.log(res)
  return {
    name: 'svg-transform',
    transformIndexHtml (dom: string) {
      return dom.replace(
        '<body>',
          `<body><svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" style="position: absolute; width: 0; height: 0" version="1.1">${res.join('')}</svg>`
      )
    }
  }
}
