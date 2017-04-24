package magenta.deployment_type

import magenta.Datum
import magenta.deployment_type.param_reads.PatternValue
import magenta.tasks.S3Upload

object S3 extends DeploymentType {
  val name = "aws-s3"
  val documentation = "For uploading files into an S3 bucket."

  val prefixStage = Param("prefixStage", "Prefix the S3 bucket key with the target stage").default(true)
  val prefixPackage = Param("prefixPackage", "Prefix the S3 bucket key with the package name").default(true)
  val prefixStack = Param("prefixStack", "Prefix the S3 bucket key with the target stack").default(true)
  val pathPrefixResource = Param[String](
    "pathPrefixResource",
    """Deploy Info resource key to use to look up an additional prefix for the path key. Note that this will override
       the `prefixStage`, `prefixPackage` and `prefixStack` keys - none of those prefixes will be applied, as you have
       full control over the path with the resource lookup.
    """.stripMargin,
    optionalInYaml = true
  )

  //required configuration, you cannot upload without setting these
  val bucket = Param[String](
    "bucket",
    "S3 bucket to upload package files to (see also `bucketResource`)",
    optionalInYaml = true
  )

  val bucketResource = Param[String](
    "bucketResource",
    """Deploy Info resource key to use to look up the S3 bucket to which the package files should be uploaded.
      |
      |This parameter is mutually exclusive with `bucket`, which can be used instead if you upload to the same bucket
      |regardless of the target stage.
    """.stripMargin,
    optionalInYaml = true
  )

  val publicReadAcl = Param[Boolean](
    "publicReadAcl",
    "Whether the uploaded artifacts should be given the PublicRead Canned ACL. (Default is true!)"
  ).default(true)

  val cacheControl = Param[List[PatternValue]](
    "cacheControl",
    """
      |Set the cache control header for the uploaded files. This can take two forms, but in either case the format of
      |the cache control value itself must be a valid HTTP `Cache-Control` value such as `public, max-age=315360000`.
      |
      |In the first form the cacheControl parameter is set to a JSON string and the value will apply to all files
      |uploaded to the S3 bucket.
      |
      |In the second form the cacheControl parameter is set to an array of JSON objects, each of which have `pattern`
      |and `value` keys. `pattern` is a Java regular expression whilst `value` must be a valid `Cache-Control` header.
      |For each file being uploaded, the array of regular expressions is evaluated against the file path and name. The
      |first regular expression that matches the file is used to determine the cache control value. If there is no match
      |then no cache control will be set.
      |
      |In the example below, if the file path matches either of the first two regular expressions then the cache control
      |header will be set to ten years (i.e. never expire). If neither of the first two match then the catch all regular
      |expression of the last object will match, setting the cache control header to one hour.
      |
      |    "cacheControl": [
      |      {
      |        "pattern": "^js/lib/",
      |        "value": "max-age=315360000"
      |      },
      |      {
      |        "pattern": "^\d*\.\d*\.\d*/",
      |        "value": "max-age=315360000"
      |      },
      |      {
      |        "pattern": ".*",
      |        "value": "max-age=3600"
      |      }
      |    ]
    """.stripMargin
  )

  val mimeTypes = Param[Map[String, String]](
    "mimeTypes",
    """
      |A map of file extension to MIME type.
      |
      |When a file is uploaded with a file extension that is in this map, the Content-Type header will be set to the
      |MIME type provided.
      |
      |The example below adds the MIME type for Firefox plugins so that they install correctly rather than opening in
      |the browser.
      |
      |    "mimeTypes": {
      |      "xpi": "application/x-xpinstall"
      |    }
    """.stripMargin
  ).default(Map.empty)

  val uploadStaticFiles = Action(
    name = "uploadStaticFiles",
    documentation =
      """
        |Uploads the deployment files to an S3 bucket. In order for this to work, magenta must have credentials that are
        |valid to write to the bucket in the specified location.
        |
        |Each file path and name is used to generate the key, optionally prefixing the target stage and the package name
        |to the key. The generated key looks like: `/<bucketName>/<targetStage>/<packageName>/<filePathAndName>`.
        |
        |Alternatively, you can specify a pathPrefixResource (eg `s3-path-prefix`) to lookup the path prefix, giving you
        |greater control. The generated key looks like: `/<pathPrefix>/<filePathAndName>`.
        """.stripMargin
  ) { (pkg, resources, target) =>
    {
      def resourceLookupFor(resource: Param[String]): Option[Datum] = {
        resource.get(pkg).flatMap { resourceName =>
          assert(
            pkg.apps.size == 1,
            s"The $name package type, in conjunction with ${resource.name}, only be used when exactly one app is specified - you have [${pkg.apps.map(_.name).mkString(",")}]"
          )
          val dataLookup = resources.lookup.data
          val app = pkg.apps.head
          val datumOpt = dataLookup.datum(resourceName, app, target.parameters.stage, target.stack)
          if (datumOpt.isEmpty) {
            def str(f: Datum => String) = s"[${dataLookup.get(resourceName).map(f).toSet.mkString(", ")}]"
            resources.reporter.verbose(
              s"No datum found for resource=$resourceName app=$app stage=${target.parameters.stage} stack=${target.stack} - values *are* defined for app=${str(
                _.app)} stage=${str(_.stage)} stack=${str(_.stack.mkString)}")
          }
          datumOpt
        }
      }

      implicit val keyRing = resources.assembleKeyring(target, pkg)
      implicit val artifactClient = resources.artifactClient
      val reporter = resources.reporter

      assert(bucket.get(pkg).isDefined != bucketResource.get(pkg).isDefined,
             "One, and only one, of bucket or bucketResource must be specified")
      val bucketName = bucket.get(pkg) getOrElse {
        val data = resourceLookupFor(bucketResource)
        assert(
          data.isDefined,
          s"Cannot find resource value for ${bucketResource(pkg, target, reporter)} (${pkg.apps.head} in ${target.parameters.stage.name})")
        data.get.value
      }

      val prefix: String = resourceLookupFor(pathPrefixResource)
        .map(_.value)
        .getOrElse(S3Upload.prefixGenerator(
          stack = if (prefixStack(pkg, target, reporter)) Some(target.stack) else None,
          stage = if (prefixStage(pkg, target, reporter)) Some(target.parameters.stage) else None,
          packageName = if (prefixPackage(pkg, target, reporter)) Some(pkg.name) else None
        ))
      List(
        S3Upload(
          target.region,
          bucket = bucketName,
          paths = Seq(pkg.s3Package -> prefix),
          cacheControlPatterns = cacheControl(pkg, target, reporter),
          extensionToMimeType = mimeTypes(pkg, target, reporter),
          publicReadAcl = publicReadAcl(pkg, target, reporter)
        )
      )
    }
  }

  def defaultActions = List(uploadStaticFiles)
}
